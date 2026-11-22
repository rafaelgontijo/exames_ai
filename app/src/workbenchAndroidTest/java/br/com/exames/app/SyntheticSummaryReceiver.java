package br.com.exames.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import java.io.InputStream;
import java.security.MessageDigest;

/** Test APK, separate UID. Accepts only the disposable prototype package's synthetic exports. */
public final class SyntheticSummaryReceiver extends Activity {
    static final String SOURCE = "br.com.exames.offline.validation.prototype";
    static volatile String lastDigest = "";

    static boolean eligible(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) && (SOURCE + ".resultshare").equals(uri.getAuthority());
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent send = getIntent();
        Uri uri = send.getParcelableExtra(Intent.EXTRA_STREAM);
        if (!Intent.ACTION_SEND.equals(send.getAction()) || !"text/plain".equals(send.getType())
                || !eligible(uri) || send.hasExtra(Intent.EXTRA_TEXT) || send.getClipData() == null
                || !uri.equals(send.getClipData().getItemAt(0).getUri())
                || (send.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            finish(); return;
        }
        new Thread(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[4096];
                int total = 0, n;
                while ((n = stream.read(buffer)) != -1) {
                    total += n;
                    if (total > 65536) return;
                    digest.update(buffer, 0, n);
                }
                lastDigest = Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
            } catch (Exception ignored) { lastDigest = "FAILED"; }
            finally { runOnUiThread(this::finish); }
        }).start();
    }
}
