package br.com.exames.datadocuments

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.camera.core.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * Drives the real CameraX adapter (the emulated back camera on emulators). Nothing is shown or kept: pages are inspected
 * for structure only and wiped. Skipped when the device has no back camera.
 */
class CameraXPageCapturerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val surfaceExecutor = Executors.newSingleThreadExecutor()
    private val surfaceProvider = Preview.SurfaceProvider { request ->
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(request.resolution.width, request.resolution.height) }
        val surface = Surface(texture)
        request.provideSurface(surface, surfaceExecutor) { surface.release(); texture.release() }
    }
    // `pm grant` works on every supported API; UiAutomation.grantRuntimePermission needs API 28.
    private fun grantCamera() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")).use { it.readBytes() }
    }
    private fun hasBackCamera() = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA)
    private fun roots() = listOfNotNull(context.cacheDir, context.filesDir, context.noBackupFilesDir, context.externalCacheDir, context.getExternalFilesDir(null))
    private fun snapshot() = roots().flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }.associate { it.path to it.length() }
    /** AndroidX ProfileInstaller bookkeeping (24 bytes, no content from the app); everything else is forbidden. */
    private fun isKnownLibraryMarker(path: String, size: Long) = path.endsWith("/files/profileInstalled") && size <= 64

    @Test fun capturingBeforeOpenOrAfterCloseIsRejectedWithoutTouchingTheCamera() = runBlocking {
        val capturer = CameraXPageCapturer(context)
        try { capturer.capture(); fail("Expected CAMERA_NOT_OPEN") } catch (e: CaptureRejected) { assertEquals("CAMERA_NOT_OPEN", e.code) }
        capturer.close()
        try { capturer.capture(); fail("Expected CAMERA_NOT_OPEN") } catch (e: CaptureRejected) { assertEquals("CAMERA_NOT_OPEN", e.code) }
    }

    @Test fun cameraDeliversNormalizedMetadataFreePagesRepeatedlyAndLeavesNoFile() = runBlocking {
        assumeTrue("no camera on this device", hasBackCamera())
        grantCamera()
        val owner = TestOwner()
        val capturer = CameraXPageCapturer(context)
        val before = snapshot()
        withContext(Dispatchers.Main) {
            owner.registry.currentState = Lifecycle.State.RESUMED
            capturer.open(owner, surfaceProvider)
        }
        try {
            // Two captures in a row: the second can only succeed if the first ImageProxy was closed.
            repeat(2) { index ->
                val page = withContext(Dispatchers.Main) { withTimeout(60_000) { capturer.capture() } }
                try {
                    assertTrue("JPEG signature #$index", page.jpeg.size > 4 && page.jpeg[0] == 0xff.toByte() && page.jpeg[1] == 0xd8.toByte())
                    assertTrue(page.jpeg.size <= DocumentLimits.MAX_BYTES)
                    assertTrue(maxOf(page.width, page.height) <= DocumentLimits.MAX_RASTER_SIDE)
                    assertEquals(1, ImageOrientation.read(page.jpeg))
                    assertFalse("no EXIF/GPS", String(page.jpeg, Charsets.ISO_8859_1).contains("Exif"))
                    val decoded = BitmapFactory.decodeByteArray(page.jpeg, 0, page.jpeg.size)
                    assertEquals(page.width to page.height, decoded.width to decoded.height)
                    decoded.recycle()
                    ValidatedDocumentInput.read(java.io.ByteArrayInputStream(page.jpeg), "image/jpeg")
                } finally { page.wipe() }
            }
        } finally {
            withContext(Dispatchers.Main) { capturer.close(); owner.registry.currentState = Lifecycle.State.DESTROYED }
            surfaceExecutor.shutdown()
        }
        val created = snapshot().filter { (path, size) -> before[path] != size }.filterNot { (path, size) -> isKnownLibraryMarker(path, size) }
        assertTrue("camera capture must not create files, found ${created.keys}", created.isEmpty())
        // And nothing new anywhere holds an image signature, whatever its name.
        for (file in snapshot().keys.map(::File).filter { it.path !in before }) {
            val head = file.inputStream().use { it.readNBytes(3) }
            assertFalse("image bytes in ${file.name}", head.size == 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte())
        }
    }
}
