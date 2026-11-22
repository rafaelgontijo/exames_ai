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
import java.nio.charset.StandardCharsets;

/** Supplies the one-shot USB stream to the real Android picker in the isolated test package. */
public final class AuthorizedExamDocumentsProvider extends DocumentsProvider {
    static final String TITLE = "Ensaio do laudo";
    static final String AUTHORIZED_LABEL = "Laudo autorizado.pdf";
    static final String CONTROL_LABEL = "Controle local.pdf";
    private static final String TARGET = "br.com.exames.offline.validation.extraction";
    private static final String ROOT = "exam-root";
    private static final String AUTHORIZED = "authorized";
    private static final String CONTROL = "control";
    private static final String FIFO = "authorized-example.pipe";
    private static final String PDF = "application/pdf";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? new String[] {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
                Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES } : projection);
        if ((TARGET + ".test").equals(getContext().getPackageName())) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            put(cursor, row, Root.COLUMN_ROOT_ID, ROOT);
            put(cursor, row, Root.COLUMN_DOCUMENT_ID, ROOT);
            put(cursor, row, Root.COLUMN_TITLE, TITLE);
            put(cursor, row, Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY);
            put(cursor, row, Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda);
            put(cursor, row, Root.COLUMN_MIME_TYPES, PDF);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (!ROOT.equals(id) && !CONTROL.equals(id) && !AUTHORIZED.equals(id)) throw new FileNotFoundException();
        MatrixCursor cursor = documents(projection);
        addDocument(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        if (!ROOT.equals(parent)) throw new FileNotFoundException();
        MatrixCursor cursor = documents(projection);
        addDocument(cursor, AuthorizedExampleProvider.pipeReady(getContext(), FIFO) ? AUTHORIZED : CONTROL);
        return cursor;
    }

    private static MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] {Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS} : projection);
    }

    private static void addDocument(MatrixCursor cursor, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        put(cursor, row, Document.COLUMN_DOCUMENT_ID, id);
        put(cursor, row, Document.COLUMN_DISPLAY_NAME,
                ROOT.equals(id) ? TITLE : AUTHORIZED.equals(id) ? AUTHORIZED_LABEL : CONTROL_LABEL);
        put(cursor, row, Document.COLUMN_MIME_TYPE, ROOT.equals(id) ? Document.MIME_TYPE_DIR : PDF);
        put(cursor, row, Document.COLUMN_FLAGS, 0);
    }

    private static void put(MatrixCursor cursor, MatrixCursor.RowBuilder row, String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation) throws FileNotFoundException {
        if (!TARGET.equals(getCallingPackage()) || !"r".equals(mode)) throw new FileNotFoundException();
        if (AUTHORIZED.equals(id)) return AuthorizedExampleProvider.openPipe(getContext(), FIFO);
        if (!CONTROL.equals(id)) throw new FileNotFoundException();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write("%PDF-1.7\ntransport only\n%%EOF".getBytes(StandardCharsets.US_ASCII));
            }
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException("CONTROL_TRANSPORT_FAILED");
        }
    }
}
