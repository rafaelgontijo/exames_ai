package br.com.exames.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import br.com.exames.fixtures.SyntheticPdf;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test-APK process only; no Kotlin dependency shared with the instrumented target APK. */
public final class WorkbenchSyntheticProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://br.com.exames.validation.workbench.synthetic-import/fixture.pdf");
    public static final Uri CORPUS_URI = Uri.parse("content://br.com.exames.validation.workbench.synthetic-import/corpus.zip");
    private volatile boolean revoked;
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return CORPUS_URI.equals(uri) ? "application/zip" : "application/pdf"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor result = new MatrixCursor(new String[] { OpenableColumns.SIZE });
        result.addRow(new Object[] { 1L });
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ((!URI.equals(uri) && !CORPUS_URI.equals(uri)) || !"r".equals(mode) || revoked) throw new FileNotFoundException("SYNTHETIC_SOURCE_UNAVAILABLE");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (CORPUS_URI.equals(uri)) {
                        try (java.io.InputStream input = getContext().getAssets().open("corpus.zip")) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer,0,count);
                        }
                    } else output.write(SyntheticPdf.biochemistry(false));
                } catch (Exception ignored) { }
            }).start();
            return pipe[0];
        } catch (IOException ignored) { throw new FileNotFoundException("SYNTHETIC_PIPE_UNAVAILABLE"); }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("allow".equals(method)) {
            if (!"br.com.exames.offline.validation.workbench".equals(arg) && !"br.com.exames.offline.validation.final".equals(arg)) throw new IllegalArgumentException("SYNTHETIC_TARGET_INVALID");
            revoked = false;
            getContext().grantUriPermission(arg, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().grantUriPermission(arg, CORPUS_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if ("revoke".equals(method)) {
            revoked = true;
            getContext().revokeUriPermission(URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().revokeUriPermission(CORPUS_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else throw new IllegalArgumentException("SYNTHETIC_CONTROL_INVALID");
        return Bundle.EMPTY;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("READ_ONLY"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
