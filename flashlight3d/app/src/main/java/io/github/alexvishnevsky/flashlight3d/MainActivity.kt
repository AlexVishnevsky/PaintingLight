package io.github.alexvishnevsky.flashlight3d

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View as AndroidView
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything is laid out in "device space": metres, origin at the centre of the
 * screen, x right, y up, z out of the glass towards the viewer. The screen is a
 * window onto a black void; the flashlight touches the glass with its lens end
 * and leans away into the depth, and is drawn through an off-axis frustum whose
 * apex is the viewer's eye. Device space is then rotated into the world by the
 * gyro, which is what keeps the reflected environment still while the phone turns.
 *
 * Startup is guarded: each step is recorded, and if a launch dies half-way the
 * next one shows a report and steps down to a more conservative "compatibility
 * level" (see [safeLevel]).
 */
class MainActivity : ComponentActivity(), SensorEventListener, Choreographer.FrameCallback {

    companion object {
        private const val TAG = "Flashlight3D"
        private const val NEAR = 0.02
        private const val FAR = 20.0
        private const val STARTUP_FRAMES = 90
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private var settingsPanel: AndroidView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    /** True once Filament is up; everything below is only valid then. */
    private var running = false

    /**
     * 0: everything. 1: no camera (no head tracking, studio reflections).
     * 2: as 1, on the Vulkan driver instead of OpenGL.
     * 3: OpenGL with no post-processing and no environment texture at all.
     */
    private var safeLevel = 0

    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var skybox: Skybox
    private var environment: EnvironmentMap? = null
    private var fallbackLight: IndirectLight? = null
    private var sun = 0
    private var swapChain: SwapChain? = null

    private lateinit var materialProvider: UbershaderProvider
    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader
    private lateinit var flashlight: FilamentAsset
    private var flashlightEntities: Set<Int> = emptySet()
    private var glass: MaterialInstance? = null

    /** Flashlight placement in device space. */
    private val flashlightLocal = FloatArray(16)

    private var frontCamera: FrontCamera? = null
    private var cameraPausedForTorch = false
    private var framesRendered = 0
    private var cameraStarting = false

    // Physical half-size of the screen as reported by the display, metres.
    private var rawHalfWidth = 0.034f
    private var rawHalfHeight = 0.075f
    private val halfWidth get() = rawHalfWidth * Settings[P.SCREEN_SCALE]
    private val halfHeight get() = rawHalfHeight * Settings[P.SCREEN_SCALE]
    private val defaultEyeZ get() = Settings[P.EYE_Z] * 0.01f

    // Device orientation in Filament's world (Y up), column-major. Identity means
    // "phone held upright", which is also the fallback when there is no gyro.
    private val deviceMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val deviceSnapshot = FloatArray(16)
    private val sensorRotation = FloatArray(16)
    private val localMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)

    // Smoothed eye position in device space.
    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = 0.38f
    private var lastFrameNanos = 0L

    // Where the eye is assumed to stay, in world space, while no face is visible.
    // Rotating the phone then still produces parallax, from the gyro alone.
    private val eyeAnchor = FloatArray(3)
    private var eyeAnchorValid = false
    private var everTracked = false

    private var downX = 0f
    private var downY = 0f

    private var torchOn = false
    private var torchCameraId: String? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && running) startFrontCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Settings.load(this)
        Diagnostics.install(this)

        root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (Diagnostics.previousStartFailed(this)) {
            // The last launch died during startup. Do not repeat it blindly:
            // show what happened and offer a more conservative configuration.
            val previous = Settings[P.SAFE].roundToInt()
            val next = min(3, previous + 1)
            showStartupFailure(previous, next)
            return
        }
        startRenderer()
    }

    private fun showStartupFailure(previous: Int, next: Int) {
        root.addView(
            Panels.report(
                this,
                "Попередній запуск не вдався (режим сумісності $previous). " +
                    "Скопіюй звіт і надішли його, потім запусти в режимі $next.",
                listOf(
                    "Режим $next" to {
                        Settings[P.SAFE] = next.toFloat()
                        Diagnostics.clear(this)
                        recreate()
                    },
                    "Як було" to {
                        Diagnostics.clear(this)
                        recreate()
                    },
                ),
            ),
        )
    }

    private fun startRenderer() {
        safeLevel = Settings[P.SAFE].roundToInt()
        try {
            Diagnostics.stage(this, "native libraries")
            Utils.init()

            surfaceView = SurfaceView(this)
            root.addView(surfaceView)
            surfaceView.setOnTouchListener { _, event -> onSurfaceTouch(event) }
            displayHelper = DisplayHelper(this)
            measureScreen()

            setupFilament()
            loadFlashlight()
            Diagnostics.stage(this, "surface")
            setupSurface()
            findTorch()

            val panel = Panels.settings(this, ::onSettingChanged, ::showReport) {
                settingsPanel?.visibility = AndroidView.GONE
            }
            settingsPanel = panel
            root.addView(Panels.gearButton(this) {
                panel.visibility =
                    if (panel.visibility == AndroidView.VISIBLE) AndroidView.GONE else AndroidView.VISIBLE
            })
            root.addView(panel)

            running = true
            Diagnostics.stage(this, "first frames")
        } catch (e: Throwable) {
            // A Java-level failure: no need to die, show it right away.
            Log.e(TAG, "Startup failed", e)
            val text = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            File(filesDir, "crash.txt").writeText(text)
            running = false
            root.removeAllViews()
            val level = Settings[P.SAFE].roundToInt()
            showStartupFailure(level, min(3, level + 1))
            return
        }

    }

    /** Called once rendering has proven itself, so a camera crash is told apart from a render crash. */
    private fun beginCamera() {
        if (safeLevel != 0 || environment == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startFrontCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @Suppress("DEPRECATION")
    private fun measureScreen() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.xdpi > 50f && metrics.ydpi > 50f) {
            rawHalfWidth = metrics.widthPixels / metrics.xdpi * 0.0254f / 2f
            rawHalfHeight = metrics.heightPixels / metrics.ydpi * 0.0254f / 2f
        }
    }

    private fun setupFilament() {
        Diagnostics.stage(this, if (safeLevel == 2) "engine (Vulkan)" else "engine (OpenGL)")
        engine = if (safeLevel == 2) {
            Engine.Builder().backend(Engine.Backend.VULKAN).build()
        } else {
            Engine.Builder().backend(Engine.Backend.OPENGL).build()
        }
        Diagnostics.stage(this, "renderer, scene, view")
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())
        // Exposure 1 keeps the numbers simple: a radiance of 1.0 in the
        // environment map is "white" before tone mapping.
        camera.setExposure(1f)
        view.camera = camera
        view.scene = scene

        // Pure black, so AMOLED pixels around the flashlight stay off. Dithering
        // would otherwise sprinkle 1-bit noise over the black.
        skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine)
        scene.skybox = skybox
        view.dithering = View.Dithering.NONE
        applyRenderSettings()

        if (safeLevel < 3) {
            Diagnostics.stage(this, "environment map")
            environment = EnvironmentMap(engine, scene).also {
                it.fillStudio()
                it.publish()
            }
        } else {
            // Bare minimum: flat ambient light plus one directional light, no
            // textures of our own.
            Diagnostics.stage(this, "fallback lighting")
            fallbackLight = IndirectLight.Builder()
                .irradiance(1, floatArrayOf(0.35f, 0.36f, 0.4f))
                .intensity(1f)
                .build(engine)
            scene.indirectLight = fallbackLight
            sun = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1f, 0.96f, 0.9f)
                .intensity(4f)
                .direction(-0.4f, -0.5f, -0.75f)
                .castShadows(false)
                .build(engine, sun)
            scene.addEntity(sun)
        }
    }

    private fun applyRenderSettings() {
        val fancy = safeLevel < 3
        view.isPostProcessingEnabled = safeLevel < 3
        view.bloomOptions = view.bloomOptions.apply {
            enabled = fancy && Settings.on(P.BLOOM)
            strength = Settings[P.BLOOM_STRENGTH]
        }
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = fancy && Settings.on(P.AO)
        }
    }

    private fun loadFlashlight() {
        Diagnostics.stage(this, "model: materials")
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        Diagnostics.stage(this, "model: parse")
        val bytes = assets.open("flashlight.glb").use { it.readBytes() }
        flashlight = checkNotNull(assetLoader.createAsset(ByteBuffer.wrap(bytes))) {
            "flashlight.glb could not be parsed"
        }
        Diagnostics.stage(this, "model: textures")
        resourceLoader.loadResources(flashlight)
        flashlight.releaseSourceData()
        scene.addEntities(flashlight.entities)
        flashlightEntities = flashlight.entities.toSet()

        Diagnostics.stage(this, "model: layout")
        layoutFlashlight()
        glass = flashlight.instance.materialInstances.firstOrNull {
            it.name == "Glass" && it.material.hasParameter("emissiveFactor")
        }
        applyTorchLook()
    }

    private fun setupSurface() {
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                displayHelper.attach(renderer, surfaceView.display)
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    private fun startFrontCamera() {
        val env = environment ?: return
        if (frontCamera != null) return
        Diagnostics.stage(this, "front camera")
        cameraStarting = true
        // A camera that simply never delivers frames is not a crash.
        mainHandler.postDelayed({
            if (cameraStarting && running) {
                cameraStarting = false
                Diagnostics.started(this)
            }
        }, 8000)
        try {
            frontCamera = FrontCamera(
                this,
                env,
                { out ->
                    if (Settings.on(P.WORLD_LOCK)) {
                        synchronized(deviceMatrix) { System.arraycopy(deviceMatrix, 0, out, 0, 16) }
                    } else {
                        Matrix.setIdentityM(out, 0)
                    }
                },
                {
                    // Selfie cameras sit at the top edge, near the middle.
                    Pair(Settings[P.CAM_X] * 0.01f, rawHalfHeight + 0.003f + Settings[P.CAM_Y] * 0.01f)
                },
            ).also { it.start() }
        } catch (e: Throwable) {
            Log.e(TAG, "Front camera could not be started", e)
        }
    }

    // ---- settings ----------------------------------------------------------

    private fun onSettingChanged(param: P?) {
        if (!running) return
        layoutFlashlight()
        applyTorchLook()
        applyRenderSettings()
        if (param == null || param == P.IBL || param == P.LIVE) {
            val env = environment ?: return
            // With no live feed nothing re-uploads the map, so do it here.
            if (!Settings.on(P.LIVE) || frontCamera == null) {
                thread {
                    env.fillStudio()
                    env.publish()
                }
            }
        }
    }

    private fun showReport() {
        var overlay: AndroidView? = null
        overlay = Panels.report(this, "Звіт діагностики", listOf(
            "Перезапуск" to { recreate() },
            "Закрити" to { root.removeView(overlay) },
        ))
        root.addView(overlay)
    }

    // ---- frame loop -------------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        if (!uiHelper.isReadyToRender) return
        val chain = swapChain ?: return

        val dt = if (lastFrameNanos == 0L) 0.016f else (frameTimeNanos - lastFrameNanos) * 1e-9f
        lastFrameNanos = frameTimeNanos
        if (Settings.on(P.WORLD_LOCK)) {
            synchronized(deviceMatrix) { System.arraycopy(deviceMatrix, 0, deviceSnapshot, 0, 16) }
        } else {
            Matrix.setIdentityM(deviceSnapshot, 0)
        }
        trackEye(dt)

        // Off-axis frustum through the screen rectangle, apex at the eye.
        Matrix.setIdentityM(localMatrix, 0)
        Matrix.translateM(localMatrix, 0, eyeX, eyeY, eyeZ)
        Matrix.multiplyMM(cameraMatrix, 0, deviceSnapshot, 0, localMatrix, 0)
        camera.setModelMatrix(cameraMatrix)
        val k = NEAR / eyeZ
        camera.setProjection(
            Camera.Projection.PERSPECTIVE,
            (-halfWidth - eyeX) * k, (halfWidth - eyeX) * k,
            (-halfHeight - eyeY) * k, (halfHeight - eyeY) * k,
            NEAR, FAR,
        )

        Matrix.multiplyMM(modelMatrix, 0, deviceSnapshot, 0, flashlightLocal, 0)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(flashlight.root), modelMatrix)

        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            framesRendered++
        }

        if (framesRendered == STARTUP_FRAMES) {
            Diagnostics.started(this)
            beginCamera()
        }
        if (cameraStarting && (frontCamera?.framesProcessed ?: 0) > 10) {
            cameraStarting = false
            Diagnostics.started(this)
        }
    }

    private fun trackEye(dt: Float) {
        val d = deviceSnapshot
        val restEyeZ = defaultEyeZ
        var targetX = 0f
        var targetY = 0f
        var targetZ = restEyeZ
        val tracker = frontCamera
        val tracked = tracker != null && Settings.on(P.TRACK) &&
            SystemClock.uptimeMillis() - tracker.eyeTimeMs < Settings[P.LOST_MS].toLong()
        if (tracked && tracker != null) { // second test only for the smart cast
            targetX = tracker.eyeX
            targetY = tracker.eyeY
            targetZ = tracker.eyeZ
            // Remember where the eye is in the world, for when the face is lost.
            eyeAnchor[0] = d[0] * targetX + d[4] * targetY + d[8] * targetZ
            eyeAnchor[1] = d[1] * targetX + d[5] * targetY + d[9] * targetZ
            eyeAnchor[2] = d[2] * targetX + d[6] * targetY + d[10] * targetZ
            eyeAnchorValid = true
            everTracked = true
        } else if (Settings.on(P.GYRO)) {
            // No face: assume the head stays where it was last seen and let the
            // gyro move the phone around it.
            val restX = d[8] * restEyeZ
            val restY = d[9] * restEyeZ
            val restZ = d[10] * restEyeZ
            if (!eyeAnchorValid) {
                eyeAnchor[0] = restX
                eyeAnchor[1] = restY
                eyeAnchor[2] = restZ
                eyeAnchorValid = true
            } else if (!everTracked) {
                // Never had a face (no camera): there is no real position to hold
                // on to, so drift back to straight-on instead of staying skewed.
                val r = 1f - exp(-dt / 4f)
                eyeAnchor[0] += (restX - eyeAnchor[0]) * r
                eyeAnchor[1] += (restY - eyeAnchor[1]) * r
                eyeAnchor[2] += (restZ - eyeAnchor[2]) * r
            }
            targetX = d[0] * eyeAnchor[0] + d[1] * eyeAnchor[1] + d[2] * eyeAnchor[2]
            targetY = d[4] * eyeAnchor[0] + d[5] * eyeAnchor[1] + d[6] * eyeAnchor[2]
            targetZ = d[8] * eyeAnchor[0] + d[9] * eyeAnchor[1] + d[10] * eyeAnchor[2]
            // Keep the eye in front of the glass however far the phone is turned.
            targetZ = max(0.15f, targetZ)
            targetX = targetX.coerceIn(-0.5f, 0.5f)
            targetY = targetY.coerceIn(-0.5f, 0.5f)
        } else {
            eyeAnchorValid = false
        }

        if (Settings.on(P.LOCK_Z)) targetZ = restEyeZ
        targetX *= Settings[P.STRENGTH_X] * if (Settings.on(P.INVERT_X)) -1f else 1f
        targetY *= Settings[P.STRENGTH_Y] * if (Settings.on(P.INVERT_Y)) -1f else 1f

        // Sideways motion must feel immediate; distance is noisier, so it gets
        // a slower filter.
        val tauXY = Settings[P.SMOOTH_XY] * 0.001f
        val tauZ = Settings[P.SMOOTH_Z] * 0.001f
        val a = if (tauXY <= 0.001f) 1f else 1f - exp(-dt / tauXY)
        val az = if (tauZ <= 0.001f) 1f else 1f - exp(-dt / tauZ)
        eyeX += (targetX - eyeX) * a
        eyeY += (targetY - eyeY) * a
        eyeZ = max(0.12f, eyeZ + (targetZ - eyeZ) * az)
    }

    /**
     * Places the flashlight: centred, lens end touching the glass, tail leaning
     * back into the depth, sized so that from the default eye it covers the
     * configured share of the screen height.
     */
    private fun layoutFlashlight() {
        val box = flashlight.boundingBox
        val c = box.center
        val e = box.halfExtent
        val corner = FloatArray(4)
        val p = FloatArray(4)
        val m = FloatArray(16)
        val ys = FloatArray(8)
        val zs = FloatArray(8)
        val eye = defaultEyeZ
        val wanted = Settings[P.SIZE] * 2f * halfHeight
        var lo = 0.05f
        var hi = 8f
        repeat(30) {
            val scale = (lo + hi) * 0.5f
            // Orientation and size only; the offset is solved for below.
            Matrix.setIdentityM(m, 0)
            Matrix.rotateM(m, 0, Settings[P.ROLL], 0f, 0f, 1f)
            Matrix.rotateM(m, 0, Settings[P.LEAN], 1f, 0f, 0f)
            Matrix.rotateM(m, 0, 180f, 0f, 0f, 1f) // lens (model -Y) points up
            Matrix.rotateM(m, 0, Settings[P.SPIN], 0f, 1f, 0f)
            Matrix.scaleM(m, 0, scale, scale, scale)
            Matrix.translateM(m, 0, -c[0], -c[1], -c[2])

            var maxZ = -Float.MAX_VALUE
            for (i in 0 until 8) {
                corner[0] = c[0] + if (i and 1 == 0) -e[0] else e[0]
                corner[1] = c[1] + if (i and 2 == 0) -e[1] else e[1]
                corner[2] = c[2] + if (i and 4 == 0) -e[2] else e[2]
                corner[3] = 1f
                Matrix.multiplyMV(p, 0, m, 0, corner, 0)
                ys[i] = p[1]
                zs[i] = p[2]
                maxZ = max(maxZ, p[2])
            }
            // Push back until the nearest point sits at the configured gap
            // behind the glass.
            val dz = -maxZ - Settings[P.GAP] * 0.01f

            // Height on the screen plane as seen from the default eye, and the
            // vertical shift that centres it. Perspective makes the shift act
            // differently on near and far corners, hence the few refinements.
            var top = 0f
            var bottom = 0f
            var dy = 0f
            repeat(6) {
                top = -Float.MAX_VALUE
                bottom = Float.MAX_VALUE
                for (i in 0 until 8) {
                    val y = (ys[i] + dy) * eye / max(0.01f, eye - (zs[i] + dz))
                    top = max(top, y)
                    bottom = min(bottom, y)
                }
                dy -= (top + bottom) * 0.5f
            }
            if (top - bottom > wanted) hi = scale else lo = scale

            Matrix.setIdentityM(localMatrix, 0)
            Matrix.translateM(
                localMatrix, 0,
                Settings[P.OFFSET_X] * 0.01f, dy + Settings[P.OFFSET_Y] * 0.01f, dz,
            )
            Matrix.multiplyMM(flashlightLocal, 0, localMatrix, 0, m, 0)
        }
    }

    // ---- device orientation ----------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(sensorRotation, event.values)
        // sensorRotation is row-major and maps device axes to a Z-up world.
        // Only the world needs converting to Filament's Y-up:
        // (x, y, z) -> (x, z, -y).
        val r = sensorRotation
        synchronized(deviceMatrix) {
            val m = deviceMatrix
            for (col in 0 until 3) {
                m[col * 4] = r[col]
                m[col * 4 + 1] = r[8 + col]
                m[col * 4 + 2] = -r[4 + col]
                m[col * 4 + 3] = 0f
            }
            m[12] = 0f
            m[13] = 0f
            m[14] = 0f
            m[15] = 1f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- touch: tap the flashlight to switch it on ----------------------

    private fun onSurfaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    // Ask Filament which object is under the finger; only a tap on
                    // the flashlight itself counts. Picking uses a bottom-left origin.
                    view.pick(event.x.toInt(), surfaceView.height - event.y.toInt(), mainHandler) {
                        if (it.renderable in flashlightEntities) setTorch(!torchOn)
                    }
                }
            }
        }
        return true
    }

    // ---- the real LED -----------------------------------------------------

    private fun findTorch() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            torchCameraId = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.w(TAG, "No torch available", e)
        }
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        applyTorchLook()

        val id = torchCameraId ?: return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.setTorchMode(id, on)
        } catch (e: Exception) {
            // Some phones will not light the LED while any camera is open.
            // Give up the front camera for as long as the torch is on.
            if (on && frontCamera != null && !cameraPausedForTorch) {
                Log.w(TAG, "Torch refused, releasing the front camera", e)
                cameraPausedForTorch = true
                frontCamera?.pause()
                mainHandler.postDelayed({
                    if (torchOn) {
                        try {
                            manager.setTorchMode(id, true)
                        } catch (e2: Exception) {
                            Log.w(TAG, "Could not switch the torch", e2)
                        }
                    }
                }, 400)
            } else {
                Log.w(TAG, "Could not switch the torch", e)
            }
        }
        if (!on && cameraPausedForTorch) {
            cameraPausedForTorch = false
            frontCamera?.resume()
        }
    }

    private fun applyTorchLook() {
        val g = glass ?: return
        if (torchOn) {
            val glow = Settings[P.GLOW]
            g.setParameter("emissiveFactor", glow, glow * 0.91f, glow * 0.71f)
        } else {
            g.setParameter("emissiveFactor", 0f, 0f, 0f)
        }
    }

    // ---- lifecycle --------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (!running) return
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
        val sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!running) return
        choreographer.removeFrameCallback(this)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        if (torchOn) setTorch(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
        frontCamera?.shutdown()
        uiHelper.detach()

        assetLoader.destroyAsset(flashlight)
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()

        engine.destroySkybox(skybox)
        environment?.destroy()
        fallbackLight?.let { engine.destroyIndirectLight(it) }
        if (sun != 0) {
            engine.destroyEntity(sun)
            EntityManager.get().destroy(sun)
        }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        val cameraEntity = camera.entity
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
