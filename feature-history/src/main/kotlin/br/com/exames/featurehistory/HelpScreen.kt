package br.com.exames.featurehistory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import br.com.exames.coreui.*

@Composable
fun HelpScreen(onExams: () -> Unit) = ExamesScreen("Ajuda") {
    Text("Como revisar uma leitura e o que acontece com seus dados.", color = ExamesTheme.palette.secondaryText)
    HelpContent.sections.forEach { s ->
        ExamesCard(Modifier.testTag("help-section")) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, color = ExamesTheme.palette.title, modifier = Modifier.semantics { heading() })
            Text(s.body)
        }
    }
    ExamesCard(Modifier.testTag("help-details")) {
        Text(HelpContent.DETAILS_TITLE, style = MaterialTheme.typography.titleMedium, color = ExamesTheme.palette.title, modifier = Modifier.semantics { heading() })
        Text(HelpContent.DETAILS_BODY)
        SecondaryAction(HelpContent.DETAILS_ACTION, onExams)
    }
}
