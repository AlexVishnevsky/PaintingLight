package io.github.alexvishnevsky.flashlight3d

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A procedural HDR "photo studio" generated at startup: a dim gradient plus a few
 * bright soft boxes. It is the placeholder for the camera-fed cubemap; the upload
 * path (float cubemap -> prefiltered mip chain -> IndirectLight) is the same one
 * live camera data will go through.
 */
class StudioEnvironment(engine: Engine) {
    val texture: Texture
    val indirectLight: IndirectLight

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

    init {
        val size = 256
        val faceBytes = size * size * 3 * 4
        val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        val rgb = FloatArray(3)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumW = 0.0
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
                    val len2 = dx * dx + dy * dy + dz * dz
                    val inv = 1f / sqrt(len2)
                    radiance(dx * inv, dy * inv, dz * inv, rgb)
                    floats.put(rgb)
                    // solid angle weight of a cube map texel
                    val w = 1.0 / (len2 * sqrt(len2.toDouble()))
                    sumR += rgb[0] * w
                    sumG += rgb[1] * w
                    sumB += rgb[2] * w
                    sumW += w
                }
            }
        }

        texture = Texture.Builder()
            .width(size)
            .height(size)
            .levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .build(engine)
        val descriptor = Texture.PixelBufferDescriptor(buffer, Texture.Format.RGB, Texture.Type.FLOAT)
        val offsets = IntArray(6) { it * faceBytes }
        texture.generatePrefilterMipmap(engine, descriptor, offsets, Texture.PrefilterOptions())

        // Diffuse term: a single SH band holding the average radiance is plenty
        // for an object that is almost entirely metal.
        val ambient = floatArrayOf(
            (sumR / sumW).toFloat(), (sumG / sumW).toFloat(), (sumB / sumW).toFloat(),
        )
        indirectLight = IndirectLight.Builder()
            .reflections(texture)
            .irradiance(1, ambient)
            .intensity(1f)
            .build(engine)
    }

    fun destroy(engine: Engine) {
        engine.destroyIndirectLight(indirectLight)
        engine.destroyTexture(texture)
    }

    private fun radiance(x: Float, y: Float, z: Float, out: FloatArray) {
        // Base: dark warm floor, slightly brighter cool ceiling.
        val t = y * 0.5f + 0.5f
        var r = 0.015f + 0.10f * t * t
        var g = 0.015f + 0.11f * t * t
        var b = 0.018f + 0.14f * t * t
        // Overhead disc.
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

    private companion object {
        const val PI = Math.PI.toFloat()
    }
}
