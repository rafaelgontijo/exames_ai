package br.com.exames.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import br.com.exames.coreui.ExamesScreen

// Entry point whose real content arrives in task 3.4 (Ajuda); they carry no clinical data.
@Composable
fun HelpPlaceholder() = ExamesScreen("Ajuda") {
    Text("Conferência da fonte e privacidade local.")
}
