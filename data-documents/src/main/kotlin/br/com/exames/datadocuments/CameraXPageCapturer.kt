package br.com.exames.datadocuments

import android.content.Context
import android.graphics.ImageFormat
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class ImageProxyFrame(private val proxy: ImageProxy) : CapturedFrame {
    override val isJpeg: Boolean get() = proxy.format == ImageFormat.JPEG && proxy.planes.size == 1
    override val rotationDegrees: Int get() = proxy.imageInfo.rotationDegrees
    override fun readJpeg(): ByteArray {
        val buffer = proxy.planes[0].buffer
        if (buffer.remaining() > DocumentLimits.MAX_BYTES) throw CaptureRejected("CAPTURE_TOO_LARGE")
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    override fun close() = proxy.close()
}

/**
 * Back camera, in-memory capture only: no file, MediaStore, FileProvider or cache is involved. The result is a normalized JPEG
 * that the caller owns. Binding follows the given lifecycle; [close] releases the camera and the worker thread.
 */
class CameraXPageCapturer(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var worker: ExecutorService? = null

    /** Binds preview and capture to [owner]. Throws [CaptureRejected] when no back camera exists. */
    suspend fun open(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        val cameraProvider = try { ProcessCameraProvider.awaitInstance(context) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { throw CaptureRejected("CAMERA_UNAVAILABLE") }
        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) throw CaptureRejected("CAMERA_UNAVAILABLE")
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(Size(3024, 4032), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.setSurfaceProvider(surface) }
        val imageCapture = ImageCapture.Builder().setResolutionSelector(selector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(90).build()
        cameraProvider.unbindAll()
        try { cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture) }
        catch (_: Exception) { throw CaptureRejected("CAMERA_UNAVAILABLE") }
        provider = cameraProvider; capture = imageCapture
        if (worker == null) worker = Executors.newSingleThreadExecutor()
    }

    /** Suspends until the camera delivers; cancellation discards a late frame after closing it. */
    suspend fun capture(): CapturedPage {
        val imageCapture = capture ?: throw CaptureRejected("CAMERA_NOT_OPEN")
        val executor = worker ?: throw CaptureRejected("CAMERA_NOT_OPEN")
        return suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = ImageProxyFrame(image)
                    if (!cont.isActive) { frame.close(); return }
                    val result = runCatching { PageFrameProcessor.process(frame) }
                    if (cont.isActive) cont.resumeWith(result) else result.getOrNull()?.wipe()
                }
                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(CaptureRejected("CAPTURE_CAMERA_ERROR"))
                }
            })
        }
    }

    fun close() {
        capture = null
        provider?.unbindAll(); provider = null
        worker?.shutdown(); worker = null
    }
}
