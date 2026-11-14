package io.github.alexvishnevsky.flashlight3d

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Runs the front camera and uses every frame twice: to find where the viewer's
 * eyes are (for the off-axis projection) and to paint the environment map the
 * flashlight reflects.
 */
class FrontCamera(
    private val activity: ComponentActivity,
    private val environment: EnvironmentMap,
    /** Copies the current device orientation (column-major 4x4) into the array. */
    private val readOrientation: (FloatArray) -> Unit,
    /** Where the camera lens sits relative to the screen centre, metres. */
    private val cameraOffset: () -> Pair<Float, Float>,
) {
    private companion object {
        const val TAG = "Flashlight3D"
        const val EYE_DISTANCE = 0.063f   // average adult interpupillary distance
        const val FACE_WIDTH = 0.15f
        const val FALLBACK_FOV_DEG = 62.0
    }

    // Latest eye position in device space (metres, origin at the screen centre,
    // x right, y up, z towards the viewer). Read by the render thread.
    @Volatile var eyeX = 0f; private set
    @Volatile var eyeY = 0f; private set
    @Volatile var eyeZ = 0f; private set
    @Volatile var eyeTimeMs = 0L; private set
    @Volatile var framesProcessed = 0; private set

    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build(),
    )
    private val orientation = FloatArray(16)
    private val fovLongSide = readFieldOfView()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                Log.w(TAG, "Front camera unavailable", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /** Lets go of the camera (some phones refuse to light the torch otherwise). */
    fun pause() {
        provider?.unbindAll()
        camera = null
    }

    fun resume() {
        if (camera == null && provider != null) {
            try {
                bind()
            } catch (e: Exception) {
                Log.w(TAG, "Front camera could not be restarted", e)
            }
        }
    }

    fun shutdown() {
        provider?.unbindAll()
        executor.shutdown()
        detector.close()
    }

    private fun bind() {
        val provider = provider ?: return
        val useCase = analysis ?: ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(executor, ::analyze)
                analysis = it
            }
        provider.unbindAll()
        val bound = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
        camera = bound

        // Under-expose by about a stop so lamps keep some shape instead of
        // clipping; the environment map's tone curve compensates.
        val exposure = bound.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            val step = exposure.exposureCompensationStep.toFloat()
            if (step > 0f) {
                val range = exposure.exposureCompensationRange
                val index = (-1f / step).roundToInt().coerceIn(range.lower, range.upper)
                bound.cameraControl.setExposureCompensationIndex(index)
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        try {
            val image = proxy.image ?: return
            val rotation = proxy.imageInfo.rotationDegrees
            readOrientation(orientation)

            val focalPx = (image.width * 0.5 / tan(fovLongSide * 0.5)).toFloat() / Settings[P.CAM_FOV]
            val sideways = rotation == 90 || rotation == 270
            val uprightW = if (sideways) image.height else image.width
            val uprightH = if (sideways) image.width else image.height

            val faces = Tasks.await(detector.process(InputImage.fromMediaImage(image, rotation)))
            faces.maxByOrNull { it.boundingBox.width() }?.let {
                locateEyes(it, focalPx, uprightW * 0.5f, uprightH * 0.5f)
            }

            framesProcessed++
            if (!Settings.on(P.LIVE)) return
            val planes = image.planes
            environment.splat(
                CameraFrame(
                    planes[0].buffer, planes[1].buffer, planes[2].buffer,
                    planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                    image.width, image.height, rotation, focalPx,
                ),
                orientation,
            )
            environment.publish()
        } catch (e: Exception) {
            Log.w(TAG, "Frame skipped", e)
        } finally {
            proxy.close()
        }
    }

    private fun locateEyes(face: Face, focalPx: Float, cx: Float, cy: Float) {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val u: Float
        val v: Float
        val distance: Float
        if (left != null && right != null) {
            u = (left.x + right.x) * 0.5f
            v = (left.y + right.y) * 0.5f
            val pixels = hypot(left.x - right.x, left.y - right.y)
            if (pixels < 2f) return
            distance = focalPx * EYE_DISTANCE / pixels
        } else {
            val box = face.boundingBox
            u = box.exactCenterX()
            v = box.top + box.height() * 0.4f
            distance = focalPx * FACE_WIDTH / box.width()
        }
        val z = distance.coerceIn(0.15f, 1.5f)
        // The front camera looks back at the viewer, so in its upright image
        // right is the device's left and down is the device's down.
        val (offsetX, offsetY) = cameraOffset()
        eyeX = offsetX - (u - cx) * z / focalPx
        eyeY = offsetY - (v - cy) * z / focalPx
        eyeZ = z
        eyeTimeMs = SystemClock.uptimeMillis()
    }

    /** Field of view along the sensor's long side, in radians. */
    private fun readFieldOfView(): Double {
        val fallback = Math.toRadians(FALLBACK_FOV_DEG)
        return try {
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: return fallback
            val c = manager.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal == null || sensor == null || focal <= 0f) return fallback
            val fov = 2.0 * atan(maxOf(sensor.width, sensor.height) / (2.0 * focal))
            if (fov in Math.toRadians(45.0)..Math.toRadians(100.0)) fov else fallback
        } catch (e: Exception) {
            fallback
        }
    }
}
