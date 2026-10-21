package br.com.exames.featureresults

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Empty state until parser, repositories and manual review are connected. */
@Composable
fun ResultsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Revisão dos resultados", style = MaterialTheme.typography.headlineMedium)
        Text("Nenhum resultado disponível para revisão.")
        Text("Antes de avaliar, confira valor, unidade, referência e fonte no documento original.")
        Text("A sinalização de referência não define diagnóstico ou urgência.")
        TextButton(onClick = onBack) { Text("Voltar") }
    }
}
