package br.com.exames.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.exames.coreui.ExamesTheme

class MainActivity : ComponentActivity() {
    private val exam: ExamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val state by exam.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), exam::select)
            ExamesTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    ExamScreen(
                        state = state,
                        select = { picker.launch(arrayOf("application/pdf")) },
                        cancel = exam::cancel,
                        discard = exam::discard,
                        preview = exam::preview,
                    )
                }
            }
        }
    }
}
