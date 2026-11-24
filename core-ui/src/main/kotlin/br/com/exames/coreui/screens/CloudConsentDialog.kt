package br.com.exames.coreui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.ExamesTextButton
import br.com.exames.coreui.ExamesTheme

/**
 * Explicit opt-in consent dialog before dispatching any exam band to the private cloud fallback.
 * Refusal keeps the app strictly offline without sending network traffic.
 */
@Composable
fun CloudConsentDialog(
    prompt: CloudConsentPromptUi,
    onAuthorize: () -> Unit,
    onDeclineOffline: () -> Unit,
    onDownloadLocal: (() -> Unit)? = null,
) {
    val p = ExamesTheme.palette
    AlertDialog(
        onDismissRequest = onDeclineOffline,
        title = {
            Text(
                "Usar fallback privado na nuvem?",
                style = MaterialTheme.typography.titleLarge,
                color = p.title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    prompt.reasonSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Se você autorizar, faixas do laudo serão enviadas por conexão criptografada (TLS) para processamento imediato sem retenção, sem armazenamento em disco e sem uso para treinamento.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.secondaryText,
                )
                Text(
                    "Em PDFs digitais, o app aplica tarjas locais sobre CPF, e-mail, telefone e cabeçalho de identificação antes do envio. Em páginas escaneadas sem texto digital, a tarja automática baseada em texto não se aplica.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.secondaryText,
                )
            }
        },
        confirmButton = {
            ExamesTextButton(onClick = onAuthorize) {
                Text("Autorizar nuvem privada", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Column {
                if (prompt.canDownloadLocal && onDownloadLocal != null) {
                    ExamesTextButton(onClick = onDownloadLocal) {
                        Text("Baixar modelo local")
                    }
                }
                ExamesTextButton(onClick = onDeclineOffline) {
                    Text("Continuar apenas offline")
                }
            }
        },
    )
}
