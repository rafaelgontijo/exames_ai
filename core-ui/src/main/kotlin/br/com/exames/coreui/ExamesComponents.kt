package br.com.exames.coreui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Minimum touch target for every interactive component. */
val MinTouchTarget = 48.dp
val ScreenMargin = 20.dp

enum class ChipKind { INFO, REVIEW, ALERT }

/** Grows vertically with large fonts; the label is always text, color is never the only signal. */
@Composable
fun ExamesCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ExamesTheme.palette.decorativeBorder),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
fun StatusChip(label: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val p = ExamesTheme.palette
    val (bg, fg) = when (kind) {
        ChipKind.INFO -> p.infoContainer to p.onInfoContainer
        ChipKind.REVIEW -> p.reviewContainer to p.onReviewContainer
        ChipKind.ALERT -> p.alertContainer to p.onAlertContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 56.dp).fillMaxWidth(), enabled = enabled, shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.heightIn(min = 52.dp).fillMaxWidth(), enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, ExamesTheme.palette.controlBorder),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

data class BottomDestination(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun ExamesBottomBar(destinations: List<BottomDestination>, selectedRoute: String?, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        destinations.forEach { d ->
            NavigationBarItem(
                selected = d.route == selectedRoute, onClick = { onSelect(d.route) }, icon = d.icon,
                label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = d.label },
            )
        }
    }
}

/** Scrollable page with the prototype margin; title is a heading for screen readers. */
@Composable
fun ExamesScreen(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = ScreenMargin, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = ExamesTheme.palette.title,
            modifier = Modifier.semantics { heading() })
        content()
    }
}

