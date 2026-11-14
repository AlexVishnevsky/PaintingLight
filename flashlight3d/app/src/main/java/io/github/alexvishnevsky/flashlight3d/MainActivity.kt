package io.github.alexvishnevsky.flashlight3d

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
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

class MainActivity : Activity(), SensorEventListener, Choreographer.FrameCallback {

    companion object {
        private const val TAG = "Flashlight3D"

        init {
            Utils.init()
        }

        // The flashlight floats this far behind the glass, in metres. The model
        // itself is real-size (about 18 cm long).
        private const val DISTANCE = 0.58f
        private const val FOV_DEGREES = 30.0
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    private lateinit var choreographer: Choreographer

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var skybox: Skybox
    private lateinit var environment: StudioEnvironment
    private var swapChain: SwapChain? = null

    private lateinit var materialProvider: UbershaderProvider
    private lateinit var assetLoader: AssetLoader
    private lateinit var asset: FilamentAsset
    private var glass: MaterialInstance? = null
    private val modelCenter = FloatArray(3)

    // Device orientation in Filament's world (Y up), column-major. Identity means
    // "phone held upright", which is also the fallback when there is no gyro.
    private val cameraMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val sensorRotation = FloatArray(16)
    private val localMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        WindowInsetsControllerCompat(window, surfaceView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        choreographer = Choreographer.getInstance()
        displayHelper = DisplayHelper(this)

        setupFilament()
        loadFlashlight()
        setupSurface()
        findTorch()
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

        environment = StudioEnvironment(engine)
        scene.indirectLight = environment.indirectLight
        skybox = Skybox.Builder().color(0.012f, 0.013f, 0.016f, 1f).build(engine)
        scene.skybox = skybox
    }

    private fun loadFlashlight() {
        val bytes = assets.open("flashlight.glb").use { it.readBytes() }
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        asset = checkNotNull(assetLoader.createAsset(ByteBuffer.wrap(bytes))) {
            "flashlight.glb could not be parsed"
        }
        val resourceLoader = ResourceLoader(engine)
        resourceLoader.loadResources(asset)
        resourceLoader.destroy()
        asset.releaseSourceData()
        scene.addEntities(asset.entities)

        val center = asset.boundingBox.center
        modelCenter[0] = center[0]
        modelCenter[1] = center[1]
        modelCenter[2] = center[2]

        glass = asset.instance.materialInstances.firstOrNull {
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
                camera.setProjection(
                    FOV_DEGREES, width.toDouble() / height, 0.05, 50.0, Camera.Fov.VERTICAL,
                )
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    // ---- frame loop -------------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        if (!uiHelper.isReadyToRender) return
        val chain = swapChain ?: return

        camera.setModelMatrix(cameraMatrix)

        // The flashlight is rigidly attached to the screen: its transform is
        // expressed in camera space and re-based onto the camera every frame,
        // so only the environment appears to move when the phone is tilted.
        Matrix.setIdentityM(localMatrix, 0)
        Matrix.translateM(localMatrix, 0, 0f, 0f, -DISTANCE)
        Matrix.rotateM(localMatrix, 0, tilt, 1f, 0f, 0f)
        Matrix.rotateM(localMatrix, 0, 180f, 0f, 0f, 1f) // lens (model -Y) points up
        Matrix.rotateM(localMatrix, 0, spin, 0f, 1f, 0f)
        Matrix.translateM(localMatrix, 0, -modelCenter[0], -modelCenter[1], -modelCenter[2])
        Matrix.multiplyMM(modelMatrix, 0, cameraMatrix, 0, localMatrix, 0)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), modelMatrix)

        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    // ---- device orientation ----------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(sensorRotation, event.values)
        // sensorRotation is row-major and maps device axes to a Z-up world.
        // Filament's camera uses the same axes as the device (x right, y up,
        // looking down -z), so only the world needs converting to Y-up:
        // (x, y, z) -> (x, z, -y).
        val r = sensorRotation
        val m = cameraMatrix
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- touch: drag to turn the flashlight, tap to switch it on ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
            MotionEvent.ACTION_UP -> if (!moved) toggleTorch()
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

    private fun toggleTorch() {
        setTorch(!torchOn)
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        applyTorchLook()
        val id = torchCameraId ?: return
        try {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch the torch", e)
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
        uiHelper.detach()

        assetLoader.destroyAsset(asset)
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()

        engine.destroySkybox(skybox)
        environment.destroy(engine)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        val cameraEntity = camera.entity
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
