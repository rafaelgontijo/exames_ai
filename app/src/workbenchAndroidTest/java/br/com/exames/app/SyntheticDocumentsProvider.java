package br.com.exames.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.List;
import br.com.exames.fixtures.SyntheticImage;
import br.com.exames.fixtures.SyntheticPdf;

/** The test APK exposes this SAF root of synthetic documents. */
public final class SyntheticDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "synthetic-root";
    private static final String PDF = "laudo-seis-paginas.pdf";
    private static final String SCANNED = "laudo-misto-sintetico.pdf";
    private static final String PNG = "imagem-sintetica.png";
    private static final String JPEG = "imagem-sintetica.jpg";
    private static final List<String> FILES = java.util.Arrays.asList(PDF, SCANNED, PNG, JPEG);
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    @Override public boolean onCreate() { return true; }

    private static void set(MatrixCursor cursor, MatrixCursor.RowBuilder row, String key, Object value) {
        if (cursor.getColumnIndex(key) >= 0) row.add(key, value);
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder r = c.newRow();
        set(c, r, Root.COLUMN_ROOT_ID, ROOT);
        set(c, r, Root.COLUMN_DOCUMENT_ID, ROOT);
        set(c, r, Root.COLUMN_TITLE, "Laudos sintéticos");
        set(c, r, Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY);
        set(c, r, Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda);
        set(c, r, Root.COLUMN_MIME_TYPES, "application/pdf\nimage/png\nimage/jpeg");
        return c;
    }

    private void addDocument(MatrixCursor cursor, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        set(cursor, row, Document.COLUMN_DOCUMENT_ID, id);
        set(cursor, row, Document.COLUMN_DISPLAY_NAME, ROOT.equals(id) ? "Laudos sintéticos" : id);
        String mime = ROOT.equals(id) ? Document.MIME_TYPE_DIR
                : id.endsWith(".png") ? "image/png" : id.endsWith(".jpg") ? "image/jpeg" : "application/pdf";
        set(cursor, row, Document.COLUMN_MIME_TYPE, mime);
        set(cursor, row, Document.COLUMN_FLAGS, 0);
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (!ROOT.equals(id) && !FILES.contains(id)) throw new FileNotFoundException();
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        addDocument(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!ROOT.equals(id)) throw new FileNotFoundException();
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        for (String file : FILES) addDocument(cursor, file);
        return cursor;
    }

    private InputStream syntheticInput(String id) throws Exception {
        switch (id) {
            case PDF: return getContext().getAssets().open("source.pdf");
            case SCANNED: return new ByteArrayInputStream(SyntheticPdf.hybrid());
            default: return new ByteArrayInputStream(SyntheticImage.quadrants(JPEG.equals(id), 1));
        }
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!FILES.contains(id) || !"r".equals(mode)) throw new FileNotFoundException();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                     InputStream in = syntheticInput(id)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                } catch (Exception ignored) { }
            }).start();
            return pipe[0];
        } catch (IOException e) { throw new FileNotFoundException(); }
    }
}
