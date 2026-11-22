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
    public static final Uri MULTIPAGE_URI = Uri.parse("content://br.com.exames.validation.workbench.synthetic-import/multipage.pdf");
    private volatile boolean revoked;
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return CORPUS_URI.equals(uri) ? "application/zip" : "application/pdf"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor result = new MatrixCursor(new String[] { OpenableColumns.SIZE });
        result.addRow(new Object[] { 1L });
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ((!URI.equals(uri) && !CORPUS_URI.equals(uri) && !MULTIPAGE_URI.equals(uri)) || !"r".equals(mode) || revoked) throw new FileNotFoundException("SYNTHETIC_SOURCE_UNAVAILABLE");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (CORPUS_URI.equals(uri) || MULTIPAGE_URI.equals(uri)) {
                        try (java.io.InputStream input = getContext().getAssets().open(CORPUS_URI.equals(uri) ? "corpus.zip" : "source.pdf")) {
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
        if (method.startsWith("prototype-")) {
            String[] callers = getContext().getPackageManager().getPackagesForUid(android.os.Binder.getCallingUid());
            if (callers == null || !java.util.Arrays.asList(callers).contains(SyntheticSummaryReceiver.SOURCE)) throw new SecurityException();
            long identity = android.os.Binder.clearCallingIdentity();
            try {
                Bundle result = new Bundle();
                if ("prototype-reset".equals(method)) {
                    revoked = false;
                    SyntheticSummaryReceiver.lastDigest = "";
                } else if ("prototype-authorize-example".equals(method)) {
                    Uri source = AuthorizedExampleProvider.uri(getContext());
                    getContext().grantUriPermission(SyntheticSummaryReceiver.SOURCE, source, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    result.putParcelable("source", source);
                } else if ("prototype-delivery".equals(method)) {
                    result.putString("digest", SyntheticSummaryReceiver.lastDigest);
                } else if ("prototype-probe".equals(method)) {
                    Uri uri = Uri.parse(arg);
                    if (!SyntheticSummaryReceiver.eligible(uri)) throw new IllegalArgumentException();
                    for (String mode : new String[] { "r", "w" }) {
                        try (ParcelFileDescriptor fd = getContext().getContentResolver().openFileDescriptor(uri, mode)) {
                            result.putBoolean(mode, fd != null);
                        } catch (Exception denied) { result.putBoolean(mode, false); }
                    }
                } else throw new IllegalArgumentException();
                return result;
            } finally { android.os.Binder.restoreCallingIdentity(identity); }
        }
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
