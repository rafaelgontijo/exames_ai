package br.com.exames.featureresults

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.exames.coreui.ExamesTextButton
import br.com.exames.domain.summary.SummaryTextResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface ResultSharing {
    suspend fun prepare(document: String, run: String, summary: SummaryTextResult.Rendered): Uri
    suspend fun revoke(uri: Uri)
}

internal fun summaryChooser(uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("resumo.txt", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Compartilhar resumo")
}

@Composable
internal fun ShareSummary(document: String, run: String, rendered: SummaryTextResult.Rendered, sharing: ResultSharing) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var preview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ExamesTextButton(onClick = { preview = true; failed = false }) { Text("Compartilhar resumo") }
    if (preview) AlertDialog(
        onDismissRequest = { if (!busy) preview = false },
        title = { Text("Conferir resumo TXT") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("O aplicativo que você escolher poderá guardar ou transmitir uma cópia.")
            Text(rendered.text)
            if (failed) Text("Não foi possível compartilhar o resumo.")
        } },
        dismissButton = { ExamesTextButton(onClick = { preview = false }, enabled = !busy) { Text("Cancelar") } },
        confirmButton = { ExamesTextButton(enabled = !busy, onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    var pending: Uri? = null
                    try {
                        val uri = sharing.prepare(document, run, rendered)
                        pending = uri
                        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            context.startActivity(summaryChooser(uri))
                            pending = null
                            preview = false
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally {
                        pending?.let { withContext(NonCancellable) { sharing.revoke(it) } }
                        busy = false
                    }
                }
            }
        }) { Text("Escolher aplicativo") } })
}
