package br.com.exames.featurehistory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Repository wiring, local search, reopening and delete confirmation are next steps. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Histórico local", style = MaterialTheme.typography.headlineMedium)
        Text("Nenhum laudo salvo nesta base inicial.")
        Text("Laudos e artefatos ficarão no armazenamento privado do dispositivo.")
        TextButton(onClick = onBack) { Text("Voltar") }
    }
}
