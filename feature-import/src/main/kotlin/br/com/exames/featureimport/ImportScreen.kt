package br.com.exames.featureimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ImportScreen(onResults: () -> Unit, onHistory: () -> Unit, model: ImportViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::select)
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Exames Offline", style = MaterialTheme.typography.headlineMedium)
        Text("Leitura referencial de resultados laboratoriais, no seu dispositivo.")
        Text("Não fornece diagnósticos nem recomendações clínicas.")
        Button(enabled = !state.busy, onClick = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) }) {
            Text("Selecionar documento")
        }
        if (state.busy) CircularProgressIndicator()
        state.message?.let { Text(it) }
        TextButton(onClick = onResults) { Text("Revisar resultados") }
        TextButton(onClick = onHistory) { Text("Histórico local") }
    }
}
