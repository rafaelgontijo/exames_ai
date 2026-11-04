package br.com.exames.inferenceexperimental;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/** Keeps only the synthetic experiment visible; never dismisses the device keyguard. */
public class SpikeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        TextView text = new TextView(this);
        text.setText("Teste técnico de inferência local\nSomente dados sintéticos.\nAguarde a conclusão no computador.");
        text.setTextSize(22);
        text.setPadding(32, 64, 32, 32);
        setContentView(text);
    }
}
