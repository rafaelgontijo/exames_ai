package br.com.exames.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;

/** Test-only, one-shot USB stream. The FIFO does not store bytes on the filesystem. */
public final class AuthorizedExampleProvider extends ContentProvider {
    static Uri uri(Context context) { return uri(context, false); }

    static Uri uri(Context context, boolean checklist) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".authorized-example")
                .appendPath(checklist ? "checklist" : "stream").build();
    }

    private String expectedTarget() {
        String testPackage = getContext().getPackageName();
        if (!testPackage.endsWith(".test")) return "";
        return testPackage.substring(0, testPackage.length() - 5);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri source) {
        if (uri(getContext()).equals(source)) return "application/pdf";
        if (uri(getContext(), true).equals(source)) return "application/json";
        return null;
    }

    @Override public ParcelFileDescriptor openFile(Uri source, String mode) throws FileNotFoundException {
        boolean checklist = uri(getContext(), true).equals(source)
                && "br.com.exames.offline.validation.extraction".equals(expectedTarget());
        if ((!uri(getContext()).equals(source) && !checklist) || !"r".equals(mode)
                || !expectedTarget().equals(getCallingPackage())) {
            throw new FileNotFoundException("PRIVATE_STREAM_UNAVAILABLE");
        }
        return openPipe(getContext(), checklist ? "authorized-checklist.pipe" : "authorized-example.pipe");
    }

    static boolean pipeReady(Context context, String file) {
        try {
            return OsConstants.S_ISFIFO(Os.lstat(new File(context.getFilesDir(), file).getAbsolutePath()).st_mode);
        } catch (android.system.ErrnoException failure) {
            return false;
        }
    }

    static ParcelFileDescriptor openPipe(Context context, String file) throws FileNotFoundException {
        File fifo = new File(context.getFilesDir(), file);
        if (!pipeReady(context, file)) throw new FileNotFoundException("PRIVATE_STREAM_UNAVAILABLE");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                // The named FIFO stays under the test UID. Only the anonymous pipe crosses Binder.
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                     java.io.FileInputStream in = new java.io.FileInputStream(fifo)) {
                    if (!fifo.delete()) return;
                    byte[] buffer = new byte[8192];
                    try {
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    } finally { java.util.Arrays.fill(buffer, (byte) 0); }
                } catch (java.io.IOException ignored) { }
            }, "authorized-example").start();
            return pipe[0];
        } catch (Exception ignored) { throw new FileNotFoundException("PRIVATE_STREAM_UNAVAILABLE"); }
    }

    @Override public Cursor query(Uri uri, String[] fields, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues value, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
