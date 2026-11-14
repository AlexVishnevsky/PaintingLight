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
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
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
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Everything is laid out in "device space": metres, origin at the centre of the
 * screen, x right, y up, z out of the glass towards the viewer. The screen is a
 * window; the box and the flashlight live behind it at their true physical size
 * and are drawn through an off-axis frustum whose apex is the viewer's eye.
 * Device space is then rotated into the world by the gyro, which is what keeps
 * the reflected environment still while the phone turns.
 */
class MainActivity : ComponentActivity(), SensorEventListener, Choreographer.FrameCallback {

    companion object {
        private const val TAG = "Flashlight3D"

        init {
            Utils.init()
        }

        private const val BOX_DEPTH = 0.11f
        private const val FLASHLIGHT_Z = -0.055f
        private const val FLASHLIGHT_SCREEN_FRACTION = 0.68f
        private const val DEFAULT_EYE_Z = 0.38f
        private const val NEAR = 0.02
        private const val FAR = 20.0
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    private lateinit var choreographer: Choreographer
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var skybox: Skybox
    private lateinit var environment: EnvironmentMap
    private var swapChain: SwapChain? = null

    private lateinit var materialProvider: UbershaderProvider
    private lateinit var assetLoader: AssetLoader
    private lateinit var flashlight: FilamentAsset
    private lateinit var room: FilamentAsset
    private var flashlightEntities: Set<Int> = emptySet()
    private var glass: MaterialInstance? = null
    private var beam = 0
    private val modelCenter = FloatArray(3)
    private var modelScale = 1f
    private var lensY = 0f

    private var frontCamera: FrontCamera? = null
    private var cameraPausedForTorch = false

    // Physical half-size of the screen, metres.
    private var halfWidth = 0.034f
    private var halfHeight = 0.075f

    // Device orientation in Filament's world (Y up), column-major. Identity means
    // "phone held upright", which is also the fallback when there is no gyro.
    private val deviceMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val deviceSnapshot = FloatArray(16)
    private val sensorRotation = FloatArray(16)
    private val localMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val vec = FloatArray(8)

    // Smoothed eye position in device space.
    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = DEFAULT_EYE_Z
    private var lastFrameNanos = 0L

    // Drag state: spin around the flashlight's long axis and tilt towards the viewer.
    private var spin = 35f
    private var tilt = 24f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private var torchOn = false
    private var torchCameraId: String? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startFrontCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.setOnTouchListener { _, event -> onSurfaceTouch(event) }
        WindowInsetsControllerCompat(window, surfaceView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        choreographer = Choreographer.getInstance()
        displayHelper = DisplayHelper(this)

        measureScreen()
        setupFilament()
        loadScene()
        setupSurface()
        findTorch()

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
            halfWidth = metrics.widthPixels / metrics.xdpi * 0.0254f / 2f
            halfHeight = metrics.heightPixels / metrics.ydpi * 0.0254f / 2f
        }
    }

    private fun setupFilament() {
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())
        // Exposure 1 keeps the numbers simple: a radiance of 1.0 in the
        // environment map is "white" before tone mapping.
        camera.setExposure(1f)
        view.camera = camera
        view.scene = scene

        view.bloomOptions = view.bloomOptions.apply {
            enabled = true
            strength = 0.25f
        }
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply { enabled = true }

        environment = EnvironmentMap(engine, scene)
        environment.fillStudio()
        environment.publish()
        skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine)
        scene.skybox = skybox

        // The beam of the virtual flashlight; only in the scene while it is on.
        beam = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SPOT)
            .color(1f, 0.93f, 0.8f)
            .intensityCandela(0.05f) // the walls are only centimetres away
            .falloff(0.8f)
            .spotLightCone(0.2f, 0.6f)
            .castShadows(false)
            .position(0f, 0f, 0f)
            .direction(0f, 1f, 0f)
            .build(engine, beam)
    }

    private fun loadScene() {
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        val resourceLoader = ResourceLoader(engine)

        val bytes = assets.open("flashlight.glb").use { it.readBytes() }
        flashlight = checkNotNull(assetLoader.createAsset(ByteBuffer.wrap(bytes))) {
            "flashlight.glb could not be parsed"
        }
        resourceLoader.loadResources(flashlight)
        flashlight.releaseSourceData()
        scene.addEntities(flashlight.entities)
        flashlightEntities = flashlight.entities.toSet()

        val tile = halfWidth / 3f
        room = checkNotNull(
            assetLoader.createAsset(BoxRoom.buildGlb(halfWidth, halfHeight, BOX_DEPTH, tile)),
        ) { "room could not be built" }
        resourceLoader.loadResources(room)
        room.releaseSourceData()
        scene.addEntities(room.entities)
        resourceLoader.destroy()

        val box = flashlight.boundingBox
        val center = box.center
        val half = box.halfExtent
        modelCenter[0] = center[0]
        modelCenter[1] = center[1]
        modelCenter[2] = center[2]
        modelScale = FLASHLIGHT_SCREEN_FRACTION * halfHeight / half[1]
        lensY = center[1] - half[1] // the lens is the -Y end of the model

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
        if (frontCamera != null) return
        frontCamera = FrontCamera(
            this,
            environment,
            { out -> synchronized(deviceMatrix) { System.arraycopy(deviceMatrix, 0, out, 0, 16) } },
            0f,
            halfHeight + 0.003f, // selfie cameras sit at the top edge, near the middle
        ).also { it.start() }
    }

    // ---- frame loop -------------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        if (!uiHelper.isReadyToRender) return
        val chain = swapChain ?: return

        val dt = if (lastFrameNanos == 0L) 0.016f else (frameTimeNanos - lastFrameNanos) * 1e-9f
        lastFrameNanos = frameTimeNanos
        trackEye(dt)
        synchronized(deviceMatrix) { System.arraycopy(deviceMatrix, 0, deviceSnapshot, 0, 16) }

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

        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(room.root), deviceSnapshot)

        Matrix.setIdentityM(localMatrix, 0)
        Matrix.translateM(localMatrix, 0, 0f, 0f, FLASHLIGHT_Z)
        Matrix.rotateM(localMatrix, 0, tilt, 1f, 0f, 0f)
        Matrix.rotateM(localMatrix, 0, 180f, 0f, 0f, 1f) // lens (model -Y) points up
        Matrix.rotateM(localMatrix, 0, spin, 0f, 1f, 0f)
        Matrix.scaleM(localMatrix, 0, modelScale, modelScale, modelScale)
        Matrix.translateM(localMatrix, 0, -modelCenter[0], -modelCenter[1], -modelCenter[2])
        Matrix.multiplyMM(modelMatrix, 0, deviceSnapshot, 0, localMatrix, 0)
        tm.setTransform(tm.getInstance(flashlight.root), modelMatrix)

        if (torchOn) aimBeam()

        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun trackEye(dt: Float) {
        var targetX = 0f
        var targetY = 0f
        var targetZ = DEFAULT_EYE_Z
        val tracker = frontCamera
        if (tracker != null && SystemClock.uptimeMillis() - tracker.eyeTimeMs < 700) {
            targetX = tracker.eyeX
            targetY = tracker.eyeY
            targetZ = tracker.eyeZ
        }
        // Sideways motion must feel immediate; distance is noisier, so it gets
        // a slower filter.
        val a = 1f - exp(-dt / 0.07f)
        val az = 1f - exp(-dt / 0.25f)
        eyeX += (targetX - eyeX) * a
        eyeY += (targetY - eyeY) * a
        eyeZ = max(0.12f, eyeZ + (targetZ - eyeZ) * az)
    }

    private fun aimBeam() {
        // Lens position and direction, from model space to the world.
        vec[0] = modelCenter[0]
        vec[1] = lensY
        vec[2] = modelCenter[2]
        vec[3] = 1f
        Matrix.multiplyMV(vec, 4, modelMatrix, 0, vec, 0)
        val px = vec[4]
        val py = vec[5]
        val pz = vec[6]
        vec[0] = 0f
        vec[1] = -1f
        vec[2] = 0f
        vec[3] = 0f
        Matrix.multiplyMV(vec, 4, modelMatrix, 0, vec, 0)
        val len = sqrt(vec[4] * vec[4] + vec[5] * vec[5] + vec[6] * vec[6])
        if (len < 1e-6f) return
        val dx = vec[4] / len
        val dy = vec[5] / len
        val dz = vec[6] / len
        val lm = engine.lightManager
        val instance = lm.getInstance(beam)
        // Start just in front of the glass so the light does not hit the model itself.
        lm.setPosition(instance, px + dx * 0.003f, py + dy * 0.003f, pz + dz * 0.003f)
        lm.setDirection(instance, dx, dy, dz)
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

    // ---- touch: drag to turn the flashlight, tap it to switch it on -------

    private fun onSurfaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                if (moved) {
                    val density = resources.displayMetrics.density
                    spin += (event.x - lastX) / density * 0.6f
                    tilt = (tilt + (event.y - lastY) / density * 0.4f).coerceIn(-90f, 90f)
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> if (!moved) {
                // Ask Filament which object is under the finger; only a tap on
                // the flashlight itself counts. Picking uses a bottom-left origin.
                view.pick(event.x.toInt(), surfaceView.height - event.y.toInt(), mainHandler) {
                    if (it.renderable in flashlightEntities) setTorch(!torchOn)
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
        if (on) scene.addEntity(beam) else scene.removeEntity(beam)

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
            g.setParameter("emissiveFactor", 9f, 8.2f, 6.4f)
        } else {
            g.setParameter("emissiveFactor", 0f, 0f, 0f)
        }
    }

    // ---- lifecycle --------------------------------------------------------

    override fun onResume() {
        super.onResume()
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
        choreographer.removeFrameCallback(this)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        if (torchOn) setTorch(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(this)
        frontCamera?.shutdown()
        uiHelper.detach()

        assetLoader.destroyAsset(flashlight)
        assetLoader.destroyAsset(room)
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()

        engine.destroyEntity(beam)
        EntityManager.get().destroy(beam)
        engine.destroySkybox(skybox)
        environment.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        val cameraEntity = camera.entity
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
