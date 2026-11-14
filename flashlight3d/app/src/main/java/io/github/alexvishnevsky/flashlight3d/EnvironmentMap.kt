package io.github.alexvishnevsky.flashlight3d

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** One front camera frame in YUV_420_888, plus what is needed to project into it. */
class CameraFrame(
    val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer,
    val yRowStride: Int, val uvRowStride: Int, val uvPixelStride: Int,
    val width: Int, val height: Int,
    /** Clockwise rotation that makes the buffer upright on a portrait screen. */
    val rotation: Int,
    /** Focal length in buffer pixels. */
    val focalPx: Float,
)

/**
 * The environment the flashlight reflects: a world-locked HDR cube map kept on
 * the CPU, filled either with a procedural studio or with front camera frames
 * placed by device orientation, and re-uploaded to Filament with its own
 * roughness mip chain.
 *
 * Threading: [splat], [fillStudio] and [publish] run on one worker thread (or on
 * the main thread before the worker starts). Filament is only touched on the
 * main thread.
 */
class EnvironmentMap(private val engine: Engine, private val scene: Scene) {

    private companion object {
        const val SIZE = 128
        const val LEVELS = 8          // 128 .. 1
        const val LOBE_FROM = 4       // levels 4..7 (8 px and smaller) use wide lobes
        const val TEXELS = SIZE * SIZE * 6
        const val PI = Math.PI.toFloat()
    }

    val texture: Texture = Texture.Builder()
        .width(SIZE)
        .height(SIZE)
        .levels(LEVELS)
        .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
        .format(Texture.InternalFormat.R11F_G11F_B10F)
        .build(engine)

    private var indirectLight: IndirectLight? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    // ---- worker-side state -------------------------------------------------

    private val dirs = cubeDirections(SIZE)
    private val accum = FloatArray(TEXELS * 3)
    private val known = BooleanArray(TEXELS)
    private val fill = floatArrayOf(0.05f, 0.05f, 0.05f)
    private var live = false

    private val mips = Array(LEVELS) { FloatArray((SIZE shr it) * (SIZE shr it) * 6 * 3) }
    private val lobeSource = FloatArray(mips[LOBE_FROM].size)
    private val lobeWeights: Array<FloatArray>

    /** sRGB byte -> linear, with clipped highlights pushed into HDR range. */
    private val toneLut = FloatArray(256)
    private var lutGain = -1f
    private var lutBoost = -1f

    private fun refreshToneLut() {
        val gain = Settings[P.GAIN]
        val boost = Settings[P.BOOST]
        if (gain == lutGain && boost == lutBoost) return
        lutGain = gain
        lutBoost = boost
        for (i in 0 until 256) {
            val lin = (i / 255.0).pow(2.2)
            toneLut[i] = (gain * lin * (1.0 + boost * lin.pow(3.0))).toFloat()
        }
    }

    private class UploadSet {
        val buffers = Array(LEVELS) {
            val n = (SIZE shr it) * (SIZE shr it) * 6 * 3
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        }
        val ambient = FloatArray(3)
        var busy = false
    }

    private val sets = arrayOf(UploadSet(), UploadSet())

    init {
        // Weights that turn the 8x8 level into properly blurred low mips. A plain
        // box filter would leave the six faces visibly different on rough surfaces.
        val srcSize = SIZE shr LOBE_FROM
        val srcDirs = cubeDirections(srcSize)
        val srcCount = srcSize * srcSize * 6
        val srcSolid = FloatArray(srcCount) {
            val p = it % (srcSize * srcSize)
            val u = 2f * (p % srcSize + 0.5f) / srcSize - 1f
            val v = 2f * (p / srcSize + 0.5f) / srcSize - 1f
            val l2 = 1f + u * u + v * v
            1f / (l2 * sqrt(l2))
        }
        lobeWeights = Array(LEVELS - LOBE_FROM) { k ->
            val level = LOBE_FROM + k
            val size = SIZE shr level
            val tDirs = cubeDirections(size)
            val count = size * size * 6
            val roughness = level / (LEVELS - 1f)
            val alpha = roughness * roughness
            val exponent = max(1f, (2f / (alpha * alpha) - 2f) / 4f)
            val w = FloatArray(count * srcCount)
            for (t in 0 until count) {
                var sum = 0f
                for (s in 0 until srcCount) {
                    val d = tDirs[t * 3] * srcDirs[s * 3] +
                        tDirs[t * 3 + 1] * srcDirs[s * 3 + 1] +
                        tDirs[t * 3 + 2] * srcDirs[s * 3 + 2]
                    val x = if (d > 0f) d.pow(exponent) * srcSolid[s] else 0f
                    w[t * srcCount + s] = x
                    sum += x
                }
                for (s in 0 until srcCount) w[t * srcCount + s] /= sum
            }
            w
        }
    }

    // ---- filling -----------------------------------------------------------

    /** Procedural studio: used until (or instead of) live camera data. */
    @Synchronized
    fun fillStudio() {
        val rgb = FloatArray(3)
        for (i in 0 until TEXELS) {
            studioRadiance(dirs[i * 3], dirs[i * 3 + 1], dirs[i * 3 + 2], rgb)
            accum[i * 3] = rgb[0]
            accum[i * 3 + 1] = rgb[1]
            accum[i * 3 + 2] = rgb[2]
            known[i] = true
        }
        live = false
    }

    /**
     * Paints one camera frame into the cube map. [worldFromDevice] is the
     * column-major device orientation at the time of the frame. The front
     * camera looks along device +z; in the upright image, right is device -x
     * and down is device -y.
     */
    @Synchronized
    fun splat(frame: CameraFrame, worldFromDevice: FloatArray) {
        if (!Settings.on(P.LIVE)) return
        refreshToneLut()
        val mirrorX = if (Settings.on(P.MIRROR_X)) -1f else 1f
        val mirrorY = if (Settings.on(P.MIRROR_Y)) -1f else 1f
        val blend = Settings[P.BLEND]
        if (!live) {
            // First live frame: forget the studio.
            known.fill(false)
            live = true
        }
        val m = worldFromDevice
        val f = frame.focalPx
        val bw = frame.width
        val bh = frame.height
        val rot = frame.rotation
        val uprightW = if (rot == 90 || rot == 270) bh else bw
        val uprightH = if (rot == 90 || rot == 270) bw else bh
        val cx = uprightW * 0.5f
        val cy = uprightH * 0.5f
        val feather = 0.08f * min(bw, bh)
        val yBuf = frame.y
        val uBuf = frame.u
        val vBuf = frame.v
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var count = 0

        for (i in 0 until TEXELS) {
            val dx = dirs[i * 3]
            val dy = dirs[i * 3 + 1]
            val dz = dirs[i * 3 + 2]
            // world -> device is the transpose of the rotation
            val z = m[8] * dx + m[9] * dy + m[10] * dz
            if (z < 0.25f) continue
            val x = m[0] * dx + m[1] * dy + m[2] * dz
            val y = m[4] * dx + m[5] * dy + m[6] * dz
            val uu = cx - mirrorX * f * x / z
            val vv = cy - mirrorY * f * y / z
            if (uu < 0f || vv < 0f || uu >= uprightW || vv >= uprightH) continue
            val bx: Int
            val by: Int
            when (rot) {
                90 -> { bx = vv.toInt(); by = bh - 1 - uu.toInt() }
                270 -> { bx = bw - 1 - vv.toInt(); by = uu.toInt() }
                180 -> { bx = bw - 1 - uu.toInt(); by = bh - 1 - vv.toInt() }
                else -> { bx = uu.toInt(); by = vv.toInt() }
            }
            if (bx < 0 || by < 0 || bx >= bw || by >= bh) continue

            val yy = yBuf.get(by * frame.yRowStride + bx).toInt() and 0xff
            val uvIndex = (by shr 1) * frame.uvRowStride + (bx shr 1) * frame.uvPixelStride
            val cb = (uBuf.get(uvIndex).toInt() and 0xff) - 128
            val cr = (vBuf.get(uvIndex).toInt() and 0xff) - 128
            val r = toneLut[(yy + 1.402f * cr).toInt().coerceIn(0, 255)]
            val g = toneLut[(yy - 0.344f * cb - 0.714f * cr).toInt().coerceIn(0, 255)]
            val b = toneLut[(yy + 1.772f * cb).toInt().coerceIn(0, 255)]
            sumR += r
            sumG += g
            sumB += b
            count++

            val edge = min(min(bx, bw - 1 - bx), min(by, bh - 1 - by)) / feather
            val o = i * 3
            if (!known[i]) {
                known[i] = true
                accum[o] = r
                accum[o + 1] = g
                accum[o + 2] = b
            } else {
                val a = blend * min(1f, edge)
                accum[o] += (r - accum[o]) * a
                accum[o + 1] += (g - accum[o + 1]) * a
                accum[o + 2] += (b - accum[o + 2]) * a
            }
        }
        if (count > 0) {
            // Directions nobody has looked at yet get a dimmed average of the room.
            val k = Settings[P.FILL] / count
            fill[0] += (sumR * k - fill[0]) * 0.2f
            fill[1] += (sumG * k - fill[1]) * 0.2f
            fill[2] += (sumB * k - fill[2]) * 0.2f
        }
    }

    /** Builds the mip chain and hands it to the main thread. Skips if both buffers are in flight. */
    @Synchronized
    fun publish() {
        val set = synchronized(sets) {
            sets.firstOrNull { !it.busy }?.also { it.busy = true }
        } ?: return

        val base = mips[0]
        for (i in 0 until TEXELS) {
            val o = i * 3
            if (known[i]) {
                base[o] = accum[o]
                base[o + 1] = accum[o + 1]
                base[o + 2] = accum[o + 2]
            } else {
                base[o] = fill[0]
                base[o + 1] = fill[1]
                base[o + 2] = fill[2]
            }
        }
        for (level in 1..LOBE_FROM) downsample(mips[level - 1], mips[level], SIZE shr level)
        System.arraycopy(mips[LOBE_FROM], 0, lobeSource, 0, lobeSource.size)
        val srcCount = lobeSource.size / 3
        for (level in LOBE_FROM until LEVELS) {
            val w = lobeWeights[level - LOBE_FROM]
            val out = mips[level]
            for (t in 0 until out.size / 3) {
                var r = 0f
                var g = 0f
                var b = 0f
                val row = t * srcCount
                for (s in 0 until srcCount) {
                    val x = w[row + s]
                    r += x * lobeSource[s * 3]
                    g += x * lobeSource[s * 3 + 1]
                    b += x * lobeSource[s * 3 + 2]
                }
                out[t * 3] = r
                out[t * 3 + 1] = g
                out[t * 3 + 2] = b
            }
        }
        val last = mips[LEVELS - 1]
        for (c in 0 until 3) {
            set.ambient[c] = (last[c] + last[3 + c] + last[6 + c] + last[9 + c] + last[12 + c] + last[15 + c]) / 6f
        }
        for (level in 0 until LEVELS) {
            val fb = set.buffers[level].asFloatBuffer()
            fb.put(mips[level])
        }
        mainHandler.post { upload(set) }
    }

    private fun downsample(src: FloatArray, dst: FloatArray, size: Int) {
        val srcSize = size * 2
        for (face in 0 until 6) {
            val sFace = face * srcSize * srcSize
            val dFace = face * size * size
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val a = (sFace + (y * 2) * srcSize + x * 2) * 3
                    val b = a + 3
                    val c = a + srcSize * 3
                    val d = c + 3
                    val o = (dFace + y * size + x) * 3
                    dst[o] = (src[a] + src[b] + src[c] + src[d]) * 0.25f
                    dst[o + 1] = (src[a + 1] + src[b + 1] + src[c + 1] + src[d + 1]) * 0.25f
                    dst[o + 2] = (src[a + 2] + src[b + 2] + src[c + 2] + src[d + 2]) * 0.25f
                }
            }
        }
    }

    // ---- main thread -------------------------------------------------------

    private fun upload(set: UploadSet) {
        if (destroyed) return
        for (level in 0 until LEVELS) {
            val size = SIZE shr level
            val buffer = set.buffers[level]
            buffer.clear()
            val descriptor = if (level == LEVELS - 1) {
                // Filament reads the buffers on its own thread; the set is free
                // again once the last one has been consumed.
                Texture.PixelBufferDescriptor(
                    buffer, Texture.Format.RGB, Texture.Type.FLOAT, 1, 0, 0, 0, mainHandler,
                ) { synchronized(sets) { set.busy = false } }
            } else {
                Texture.PixelBufferDescriptor(buffer, Texture.Format.RGB, Texture.Type.FLOAT)
            }
            texture.setImage(engine, level, 0, 0, 0, size, size, 6, descriptor)
        }
        val next = IndirectLight.Builder()
            .reflections(texture)
            .irradiance(1, set.ambient)
            .intensity(Settings[P.IBL])
            .build(engine)
        scene.indirectLight = next
        indirectLight?.let { engine.destroyIndirectLight(it) }
        indirectLight = next
    }

    fun destroy() {
        destroyed = true
        scene.indirectLight = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        engine.destroyTexture(texture)
    }

    // ---- helpers -----------------------------------------------------------

    /** Unit direction of every texel, in OpenGL cube map face order and layout. */
    private fun cubeDirections(size: Int): FloatArray {
        val out = FloatArray(size * size * 6 * 3)
        var i = 0
        for (face in 0 until 6) {
            for (y in 0 until size) {
                val v = 2f * (y + 0.5f) / size - 1f
                for (x in 0 until size) {
                    val u = 2f * (x + 0.5f) / size - 1f
                    val dx: Float
                    val dy: Float
                    val dz: Float
                    when (face) {
                        0 -> { dx = 1f; dy = -v; dz = -u }
                        1 -> { dx = -1f; dy = -v; dz = u }
                        2 -> { dx = u; dy = 1f; dz = v }
                        3 -> { dx = u; dy = -1f; dz = -v }
                        4 -> { dx = u; dy = -v; dz = 1f }
                        else -> { dx = -u; dy = -v; dz = -1f }
                    }
                    val inv = 1f / sqrt(dx * dx + dy * dy + dz * dz)
                    out[i++] = dx * inv
                    out[i++] = dy * inv
                    out[i++] = dz * inv
                }
            }
        }
        return out
    }

    private class Box(
        azDeg: Float, elDeg: Float, widthDeg: Float, heightDeg: Float,
        val r: Float, val g: Float, val b: Float,
    ) {
        val az = Math.toRadians(azDeg.toDouble()).toFloat()
        val el = Math.toRadians(elDeg.toDouble()).toFloat()
        val halfW = Math.toRadians(widthDeg / 2.0).toFloat()
        val halfH = Math.toRadians(heightDeg / 2.0).toFloat()
    }

    private val boxes = listOf(
        Box(40f, 35f, 38f, 26f, 12f, 11.4f, 10.2f),      // key soft box, warm white
        Box(-70f, 10f, 6f, 60f, 14f, 17f, 22f),          // tall cool strip
        Box(180f, 20f, 60f, 8f, 9f, 5.5f, 2.5f),         // rim behind, orange
        Box(120f, -12f, 14f, 10f, 1f, 5f, 4.5f),         // teal accent
        Box(-140f, 30f, 12f, 12f, 5f, 1.2f, 4f),         // magenta accent
        Box(0f, -35f, 70f, 6f, 3f, 3f, 3.2f),            // low front line
    )

    private fun studioRadiance(x: Float, y: Float, z: Float, out: FloatArray) {
        val t = y * 0.5f + 0.5f
        var r = 0.015f + 0.10f * t * t
        var g = 0.015f + 0.11f * t * t
        var b = 0.018f + 0.14f * t * t
        val top = smooth(0.93f, 0.985f, y)
        r += 5f * top
        g += 5f * top
        b += 5.4f * top

        val az = atan2(x, -z)
        val el = asin(y.coerceIn(-1f, 1f))
        for (box in boxes) {
            var dAz = az - box.az
            if (dAz > PI) dAz -= 2 * PI
            if (dAz < -PI) dAz += 2 * PI
            val wx = 1f - smooth(box.halfW * 0.85f, box.halfW, abs(dAz))
            val wy = 1f - smooth(box.halfH * 0.85f, box.halfH, abs(el - box.el))
            val w = wx * wy
            if (w > 0f) {
                r += box.r * w
                g += box.g * w
                b += box.b * w
            }
        }
        out[0] = r
        out[1] = g
        out[2] = b
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
