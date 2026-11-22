package br.com.exames.pdfspike;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import br.com.exames.datalocal.EncryptedArtifactStore;
import br.com.exames.fixtures.SyntheticPdf;
import io.legere.pdfiumandroid.PdfDocument;
import io.legere.pdfiumandroid.PdfPage;
import io.legere.pdfiumandroid.PdfTextPage;
import io.legere.pdfiumandroid.PdfiumCore;
import io.legere.pdfiumandroid.api.AlreadyClosedBehavior;
import io.legere.pdfiumandroid.api.Config;
import io.legere.pdfiumandroid.api.LoggerInterface;
import io.legere.pdfiumandroid.api.PdfiumSource;
import io.legere.pdfiumandroid.core.jni.NativeFactoryKt;
import io.legere.pdfiumandroid.core.unlocked.PdfiumCoreU;
import io.legere.pdfiumandroid.core.unlocked.SystemLibraryLoader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in only; never loads real patient documents or emits extracted source in metrics/logs. */
@RunWith(AndroidJUnit4.class)
public final class PdfiumBufferSpikeTest {
    private static final LoggerInterface NO_LOGS = new LoggerInterface() {
        @Override public void d(String tag, String message) { }
        @Override public void e(String tag, Throwable throwable, String message) { }
    };

    @Test public void authenticatedHybridBufferProvidesTextGeometryAndRaster() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("pdfSpike")));
        Context context = ApplicationProvider.getApplicationContext();
        JSONObject report = new JSONObject();
        report.put("schemaVersion", "pdf-buffer-spike/1");
        report.put("runtime", "io.legere:pdfiumandroid:2.0.1");
        String nativeProfile = InstrumentationRegistry.getArguments().getString("pdfiumNativeProfile", "upstream");
        report.put("nativeProfile", nativeProfile);
        report.put("inputMode", "custom-authenticated-memory-source");
        report.put("api", Build.VERSION.SDK_INT); report.put("deviceModel", Build.MODEL);
        report.put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        report.put("processId", Process.myPid());
        JSONArray pages = new JSONArray(); report.put("pages", pages);
        String id = UUID.randomUUID().toString();
        EncryptedArtifactStore store = new EncryptedArtifactStore(context);
        byte[] source = SyntheticPdf.hybrid();
        Set<String> filesBefore = filesUnder(context.getDataDir());
        long start = SystemClock.elapsedRealtime();
        try {
            store.write(id, "original", new ByteArrayInputStream(source));
            byte[] authenticated = store.read(id, "original");
            assertArrayEquals(source, authenticated);
            // Explicit config before any document; no source/exception logger.
            Config config = new Config(NO_LOGS, AlreadyClosedBehavior.EXCEPTION);
            PdfiumCoreU internal = new PdfiumCoreU(context, config,
                NativeFactoryKt.getDefaultNativeFactory(), SystemLibraryLoader.INSTANCE);
            PdfiumCore core = new PdfiumCore(context, config, internal);
            report.put("pssBeforeOpenKiB", pss());
            long open = SystemClock.elapsedRealtime();
            // Custom memory source avoids nativeOpenMemDocument's new[]/free mismatch
            // and failed-open copy leak identified in the published JNI sources.
            try (MemorySource input = new MemorySource(authenticated);
                 PdfDocument document = core.newDocument(input)) {
                report.put("openMillis", SystemClock.elapsedRealtime() - open);
                assertEquals(3, document.getPageCount());
                long maxSample = pss();
                for (int index = 0; index < document.getPageCount(); index++) {
                    JSONObject metrics = new JSONObject(); metrics.put("page", index + 1);
                    try (PdfPage page = document.openPage(index); PdfTextPage text = page.openTextPage()) {
                        int count = text.textPageCountChars();
                        assertTrue(count >= 0 && count <= 100000);
                        String extracted = text.textPageGetText(0, count);
                        if (extracted == null) extracted = "";
                        if (index == 0) assertTrue("Portuguese text unavailable", extracted.contains("Sódio") && extracted.contains("Glicose"));
                        if (index == 1) assertEquals("Scanned page must have no text layer", 0, count);
                        if (index == 2) assertTrue("Rotated text unavailable", extracted.contains("Potássio"));
                        int pointWidth = page.getPageWidthPoint(), pointHeight = page.getPageHeightPoint();
                        assertTrue(pointWidth > 0 && pointHeight > 0);
                        float scale = Math.min(2f, 2048f / Math.max(pointWidth, pointHeight));
                        int width = Math.max(1, Math.round(pointWidth * scale));
                        int height = Math.max(1, Math.round(pointHeight * scale));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        try {
                            long render = SystemClock.elapsedRealtime();
                            bitmap.eraseColor(Color.WHITE);
                            page.renderPageBitmap(bitmap, 0, 0, width, height, false, false, 0, Color.WHITE);
                            metrics.put("renderMillis", SystemClock.elapsedRealtime() - render);
                            assertTrue("Blank raster", hasInk(bitmap, 0, 0, width, height));
                            int mappedGlyphs = 0;
                            for (int c = 0; c < count; c++) {
                                char unicode = text.textPageGetUnicode(c);
                                if (Character.isWhitespace(unicode) || Character.isISOControl(unicode)) continue;
                                RectF box = text.textPageGetCharBox(c);
                                assertNotNull("Missing glyph coordinates", box);
                                Point a = page.mapPageCoordsToDevice(0, 0, width, height, 0, box.left, box.top);
                                Point b = page.mapPageCoordsToDevice(0, 0, width, height, 0, box.right, box.bottom);
                                int left = Math.max(0, Math.min(a.x, b.x) - 1), top = Math.max(0, Math.min(a.y, b.y) - 1);
                                int right = Math.min(width, Math.max(a.x, b.x) + 2), bottom = Math.min(height, Math.max(a.y, b.y) + 2);
                                assertTrue("Glyph highlight misses raster", hasInk(bitmap, left, top, right, bottom));
                                mappedGlyphs++;
                            }
                            metrics.put("mappedGlyphsWithInk", mappedGlyphs);
                            metrics.put("bitmapWidth", width); metrics.put("bitmapHeight", height);
                            metrics.put("bitmapBytes", bitmap.getAllocationByteCount());
                            metrics.put("rotationQuarterTurns", page.getPageRotation());
                            maxSample = Math.max(maxSample, pss());
                        } finally { bitmap.recycle(); }
                        metrics.put("textCharacters", count);
                    }
                    pages.put(metrics);
                }
                report.put("maxSampledPssKiB", maxSample);
            } finally { Arrays.fill(authenticated, (byte) 0); }
            int rejectedInputs = 0;
            for (int attempt = 0; attempt < 8; attempt++) {
                // Sanitized handling; never copy native exception messages into the report.
                byte[] invalid = SyntheticPdf.bytes("%PDF-1.4\nSYNTHETIC-INVALID-PRIVATE-TOKEN\n%%EOF\n");
                try (MemorySource input = new MemorySource(invalid)) {
                    try (PdfDocument ignored = core.newDocument(input)) {
                        fail("Invalid PDF was accepted");
                    } catch (java.io.IOException expected) { rejectedInputs++; }
                } finally { Arrays.fill(invalid, (byte) 0); }
            }
            assertEquals(8, rejectedInputs);
            report.put("invalidDocumentsRejected", rejectedInputs);
            if (nativeProfile.startsWith("pdfium-jni-exames/")) {
                verifyCorrectedDirectBuffer(core, report);
            }
            // Authentication must fail before PDFium sees any tampered document.
            File encrypted = new File(context.getNoBackupFilesDir(), "artifacts/" + id + "/original.enc");
            byte[] ciphertext = Files.readAllBytes(encrypted.toPath());
            assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains("SYNTHETIC-PDF-PRIVATE-MARKER"));
            ciphertext[ciphertext.length - 1] ^= 1;
            Files.write(encrypted.toPath(), ciphertext);
            boolean rejected = false;
            try { store.read(id, "original"); } catch (Exception expected) { rejected = true; }
            assertTrue("Tampering accepted", rejected);
            report.put("tamperingRejectedBeforeDecode", true);
            store.deleteDocument(id);
            Set<String> created = filesUnder(context.getDataDir()); created.removeAll(filesBefore);
            // The fixture and decoded content must never become disk artifacts, including cache.
            for (String path : created) {
                File file = new File(path);
                if (file.length() < 32L * 1024 * 1024) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    assertFalse("Source marker persisted", new String(bytes, StandardCharsets.ISO_8859_1).contains("SYNTHETIC-PDF-PRIVATE-MARKER"));
                    assertFalse("Plain PDF persisted", bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"));
                }
            }
            report.put("createdFilesInspected", created.size());
            report.put("passed", true);
        } finally {
            Arrays.fill(source, (byte) 0); store.deleteDocument(id);
            report.put("elapsedMillis", SystemClock.elapsedRealtime() - start);
            Files.write(new File(context.getFilesDir(), "pdf-spike-report.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static int pss() { Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory); return memory.getTotalPss(); }
    private static void verifyCorrectedDirectBuffer(PdfiumCore core, JSONObject report) throws Exception {
        // Exercise the repaired ownership path rather than testing only the custom-source workaround.
        byte[] valid = SyntheticPdf.hybrid();
        try {
            for (int i = 0; i < 16; i++) {
                try (PdfDocument document = core.newDocument(valid);
                     PdfPage page = document.openPage(0);
                     PdfTextPage text = page.openTextPage()) {
                    assertEquals(3, document.getPageCount());
                    assertTrue(text.textPageCountChars() > 0);
                }
            }
        } finally { Arrays.fill(valid, (byte) 0); }
        byte[] concurrentSource = SyntheticPdf.hybrid();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> exercise = () -> {
                int completed = 0;
                for (int i = 0; i < 16; i++) {
                    try (PdfDocument document = core.newDocument(concurrentSource);
                         PdfPage page = document.openPage(0);
                         PdfTextPage text = page.openTextPage()) {
                        assertEquals(3, document.getPageCount());
                        assertTrue(text.textPageCountChars() > 0);
                        completed++;
                    }
                }
                return completed;
            };
            Future<Integer> first = threads.submit(exercise), second = threads.submit(exercise);
            assertEquals(32, first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
            if (threads.awaitTermination(5, TimeUnit.SECONDS)) Arrays.fill(concurrentSource, (byte) 0);
        }
        boolean nullRejected = false;
        try (PdfDocument ignored = core.newDocument((byte[]) null)) {
            fail("Null source was accepted");
        } catch (Exception expected) { nullRejected = true; }
        assertTrue(nullRejected);
        boolean emptyRejected = false;
        try (PdfDocument ignored = core.newDocument(new byte[0])) {
            fail("Empty source was accepted");
        } catch (Exception expected) { emptyRejected = true; }
        assertTrue(emptyRejected);
        byte[] invalid = new byte[4 * 1024 * 1024];
        byte[] header = SyntheticPdf.bytes("%PDF-1.4\nSYNTHETIC-INVALID-PRIVATE-TOKEN\n");
        System.arraycopy(header, 0, invalid, 0, header.length);
        long nativeBefore = Debug.getNativeHeapAllocatedSize();
        int rejected = 0;
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                try (PdfDocument ignored = core.newDocument(invalid)) {
                    fail("Invalid direct buffer was accepted");
                } catch (java.io.IOException expected) { rejected++; }
            }
        } finally { Arrays.fill(invalid, (byte) 0); }
        long delta = Debug.getNativeHeapAllocatedSize() - nativeBefore;
        assertEquals(8, rejected);
        // The old failed-open path retains 32 MiB here. Allow 16 MiB for allocator/cache variation.
        assertTrue("Native allocations retained after failed opens", delta < 16L * 1024 * 1024);
        report.put("directBufferValidOpenCloseCycles", 16);
        report.put("concurrentDocumentCycles", 32);
        report.put("concurrentThreads", 2);
        report.put("directBufferInvalidFourMiBRejected", rejected);
        report.put("directBufferNullRejected", nullRejected);
        report.put("directBufferEmptyRejected", emptyRejected);
        report.put("nativeAllocatedDeltaAfterFailedOpensBytes", delta);
        report.put("mutexSanitizerTested", false);
    }
    private static final class MemorySource implements PdfiumSource {
        private final byte[] bytes;
        private boolean closed;
        MemorySource(byte[] bytes) { this.bytes = bytes; }
        @Override public long getLength() { return bytes.length; }
        @Override public int read(long position, byte[] output, int size) {
            if (closed || position < 0 || size < 1 || size > output.length || position > bytes.length - (long) size) return 0;
            System.arraycopy(bytes, (int) position, output, 0, size);
            return size;
        }
        @Override public void close() { closed = true; }
    }
    private static boolean hasInk(Bitmap image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++)
            if ((image.getPixel(x, y) & 0x00ffffff) < 0x00dddddd) return true;
        return false;
    }
    private static Set<String> filesUnder(File root) {
        Set<String> result = new HashSet<>(); File[] files = root.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) result.addAll(filesUnder(file)); else result.add(file.getAbsolutePath());
        }
        return result;
    }
}
