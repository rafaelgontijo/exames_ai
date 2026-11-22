package br.com.exames.pdflocal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.RectF;
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
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Blocking adapter. Takes ownership of an authenticated buffer; no URI or plaintext files. */
public final class PrivatePdf implements Closeable {
    public static final String VERSION = "pdfium-8035-jni-exames2/1";
    public static final int MAX_BYTES = ExtractionLimits.MAX_BYTES;
    public static final int MAX_PAGES = ExtractionLimits.MAX_PAGES;
    public static final int MAX_SIDE = ExtractionLimits.MAX_RASTER_SIDE;
    private static final int MAX_CHARACTERS = ExtractionLimits.MAX_PDF_CHARACTERS;
    private static final LoggerInterface QUIET = new LoggerInterface() {
        public void d(String tag, String message) { }
        public void e(String tag, Throwable error, String message) { }
    };
    private final byte[] owned;
    private final MemorySource source;
    private final PdfDocument document;
    private boolean closed;
    private Page active;
    public final int pageCount;

    public PrivatePdf(Context context, byte[] authenticated) throws IOException {
        if (authenticated == null) throw failure("PDF_UNAVAILABLE");
        owned = authenticated;
        source = new MemorySource(owned);
        PdfDocument opened = null;
        try {
            if (owned.length < 5 || owned.length > MAX_BYTES) throw failure("PDF_SIZE_LIMIT");
            Config config = new Config(QUIET, AlreadyClosedBehavior.EXCEPTION);
            PdfiumCoreU internal = new PdfiumCoreU(context.getApplicationContext(), config,
                NativeFactoryKt.getDefaultNativeFactory(), SystemLibraryLoader.INSTANCE);
            opened = new PdfiumCore(context.getApplicationContext(), config, internal).newDocument(source);
            int count = opened.getPageCount();
            if (count < 1 || count > MAX_PAGES) throw failure("PDF_PAGE_LIMIT");
            pageCount = count;
            document = opened;
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            if (opened != null) { try { opened.close(); } catch (Exception ignored) { } }
            source.close();
            if (error instanceof PdfFailure) throw (PdfFailure) error;
            throw failure("PDF_OPEN_FAILED"); // No native exception/cause or document content escapes.
        }
    }

    /** One active bitmap at a time. Closing the returned page permits reading the next page. */
    public synchronized Page readPage(int oneBased) throws IOException {
        if (closed) throw failure("PDF_CLOSED");
        if (oneBased < 1 || oneBased > pageCount) throw failure("PDF_PAGE_INDEX");
        if (active != null) throw failure("PDF_PAGE_STILL_ACTIVE");
        Bitmap image = null;
        try (PdfPage page = document.openPage(oneBased - 1); PdfTextPage text = page.openTextPage()) {
            int widthPoints = page.getPageWidthPoint(), heightPoints = page.getPageHeightPoint();
            if (widthPoints < 1 || heightPoints < 1) throw failure("PDF_GEOMETRY_INVALID");
            double scale = Math.min(2.0, MAX_SIDE / (double) Math.max(widthPoints, heightPoints));
            int width = Math.max(1, (int) Math.floor(widthPoints * scale));
            int height = Math.max(1, (int) Math.floor(heightPoints * scale));
            image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.WHITE);
            page.renderPageBitmap(image, 0, 0, width, height, false, false, 0, Color.WHITE);
            int count = text.textPageCountChars();
            if (count < 0 || count > MAX_CHARACTERS) throw failure("PDF_TEXT_LIMIT");
            String raw = count == 0 ? "" : text.textPageGetText(0, count);
            if (raw == null) throw failure("PDF_TEXT_UNAVAILABLE");
            List<Glyph> glyphs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String character = String.valueOf(text.textPageGetUnicode(i));
                RectF box = text.textPageGetCharBox(i);
                int[] bounds = null;
                if (box != null && finite(box)) {
                    Point a = page.mapPageCoordsToDevice(0, 0, width, height, 0, box.left, box.top);
                    Point b = page.mapPageCoordsToDevice(0, 0, width, height, 0, box.right, box.bottom);
                    int left = Math.max(0, Math.min(a.x, b.x)), top = Math.max(0, Math.min(a.y, b.y));
                    int right = Math.min(width, Math.max(a.x, b.x)), bottom = Math.min(height, Math.max(a.y, b.y));
                    if (right > left && bottom > top) bounds = new int[] {left, top, right, bottom};
                }
                glyphs.add(new Glyph(i, character, bounds));
            }
            int rotation = page.getPageRotation();
            if (rotation < 0 || rotation > 3) throw failure("PDF_GEOMETRY_INVALID");
            active = new Page(this, oneBased, image, raw, glyphs, widthPoints, heightPoints, rotation * 90);
            return active;
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            if (image != null) image.recycle();
            active = null;
            if (error instanceof PdfFailure) throw (PdfFailure) error;
            throw failure("PDF_PAGE_FAILED");
        }
    }
    private static boolean finite(RectF box) {
        return Float.isFinite(box.left) && Float.isFinite(box.top) && Float.isFinite(box.right) && Float.isFinite(box.bottom);
    }
    private synchronized void release(Page page) {
        if (active == page) active = null;
        page.image.recycle();
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (active != null) active.close();
            document.close();
        } catch (Exception error) {
            throw failure("PDF_CLOSE_FAILED");
        } finally { source.close(); }
    }
    public static final class Page implements Closeable {
        private final PrivatePdf owner;
        private boolean closed;
        public final int page, widthPoints, heightPoints, rotationDegrees;
        public final Bitmap image;
        public final String raw;
        public final List<Glyph> glyphs;
        private Page(PrivatePdf owner, int page, Bitmap image, String raw, List<Glyph> glyphs,
                     int widthPoints, int heightPoints, int rotationDegrees) {
            this.owner = owner; this.page = page; this.image = image; this.raw = raw;
            this.glyphs = Collections.unmodifiableList(glyphs); this.widthPoints = widthPoints;
            this.heightPoints = heightPoints; this.rotationDegrees = rotationDegrees;
        }
        @Override public void close() {
            synchronized (owner) { if (!closed) { closed = true; owner.release(this); } }
        }
    }
    public static final class Glyph {
        public final int sourceIndex;
        public final String raw;
        /** Pixel coordinates of the very same bitmap, top-left origin; null when unavailable. */
        public final int[] bounds;
        private Glyph(int index, String raw, int[] bounds) { this.sourceIndex = index; this.raw = raw; this.bounds = bounds; }
    }
    private static final class MemorySource implements PdfiumSource {
        private final byte[] bytes;
        private boolean closed;
        MemorySource(byte[] bytes) { this.bytes = bytes; }
        public long getLength() { return bytes.length; }
        public int read(long position, byte[] output, int count) {
            if (closed || position < 0 || count < 1 || count > output.length || position > bytes.length - (long) count) return 0;
            System.arraycopy(bytes, (int) position, output, 0, count); return count;
        }
        public void close() { if (!closed) { closed = true; Arrays.fill(bytes, (byte) 0); } }
    }
    private static PdfFailure failure(String code) { return new PdfFailure(code); }
    /** Only a static technical code is exposed. Never retain native exception causes/messages. */
    public static final class PdfFailure extends IOException { private PdfFailure(String code) { super(code); } }
}
