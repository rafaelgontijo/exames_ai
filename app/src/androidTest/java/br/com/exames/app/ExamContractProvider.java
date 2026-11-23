package br.com.exames.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal transport probes used only for failures and encrypted storage, with no exam data. */
public final class ExamContractProvider extends ContentProvider {
    public static Uri uri(Context context, String kind) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".exam-contract")
                .appendPath(kind).build();
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri source) {
        return "wrong-mime".equals(source.getLastPathSegment()) ? "text/plain" : "application/pdf";
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        boolean checklist = "authorize-checklist".equals(method);
        if ((!checklist && !"authorize-example".equals(method)) || !trustedCaller()) throw new SecurityException();
        String caller = getCallingPackage();
        if (!"br.com.exames.offline.validation.extraction".equals(caller)) throw new SecurityException();
        Uri uri = AuthorizedExampleProvider.uri(getContext(), checklist);
        getContext().grantUriPermission(caller, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Bundle result = new Bundle();
        result.putParcelable("source", uri);
        return result;
    }

    private boolean trustedCaller() {
        return getContext().getPackageName().equals(getCallingPackage() + ".test");
    }

    @Override public ParcelFileDescriptor openFile(Uri source, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !trustedCaller()) throw new FileNotFoundException();
        String kind = source.getLastPathSegment();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if ("transport".equals(kind)) {
                    output.write("%PDF-1.7\ntransport only\n%%EOF".getBytes(StandardCharsets.US_ASCII));
                } else if ("garbage".equals(kind)) {
                    output.write("not a PDF".getBytes(StandardCharsets.US_ASCII));
                } else if (!"empty".equals(kind)) {
                    pipe[0].close();
                    throw new FileNotFoundException();
                }
            }
            return pipe[0];
        } catch (IOException failure) {
            throw new FileNotFoundException("CONTRACT_INPUT_FAILED");
        }
    }

    @Override public Cursor query(Uri uri, String[] fields, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues value, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
