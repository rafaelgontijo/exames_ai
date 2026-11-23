package br.com.exames.coreui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Minimum touch target for every interactive component. */
val MinTouchTarget = 48.dp
val ScreenMargin = 20.dp

enum class ChipKind { INFO, REVIEW, ALERT }

/** Grows vertically with large fonts; the label is always text, color is never the only signal. */
@Composable
fun ExamesCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderedControl: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(
        width = if (borderedControl) 1.5.dp else 1.dp,
        color = if (borderedControl) ExamesTheme.palette.controlBorder else ExamesTheme.palette.decorativeBorder,
    )
    val shape = MaterialTheme.shapes.medium
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = border,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = border,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
fun ExamesHeroCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    ctaText: String? = null,
    onCtaClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val p = ExamesTheme.palette
    val cardModifier = modifier.fillMaxWidth()
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.infoContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = p.onInfoContainer, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = p.onHeroContainer)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.onHeroSubtext)
            }
        }
        if (extraContent != null) {
            extraContent()
        }
        if (ctaText != null && onCtaClick != null) {
            Button(
                onClick = onCtaClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.heroActionContainer,
                    contentColor = p.onHeroActionContainer,
                ),
            ) {
                Text(ctaText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = "$title. $subtitle" },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = p.heroContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = body)
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = p.heroContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = body)
        }
    }
}

@Composable
fun ExamesReviewBanner(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    ctaText: String? = null,
    onCtaClick: (() -> Unit)? = null,
) {
    val p = ExamesTheme.palette
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = p.reviewContainer),
        border = BorderStroke(1.dp, p.reviewBorder),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(p.onReviewContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ExamesIcons.AlertCircle, contentDescription = null, tint = p.reviewContainer, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = p.onReviewContainer)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.onReviewContainer)
                }
            }
            if (ctaText != null && onCtaClick != null) {
                Button(
                    onClick = onCtaClick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = p.title,
                    ),
                    border = BorderStroke(1.dp, p.controlBorder),
                ) {
                    Icon(ExamesIcons.EyeDocument, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ctaText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ExamesTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = ExamesTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Icon(ExamesIcons.ChevronLeft, contentDescription = "Voltar", tint = p.title, modifier = Modifier.size(24.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = p.title,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
fun ExamesFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    val container = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (isSelected) MaterialTheme.colorScheme.onPrimary else p.title
    val border = if (isSelected) null else BorderStroke(1.dp, p.controlBorder)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(999.dp),
        border = border,
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = isSelected },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ExamesStepCard(stepNumber: Int, label: String, modifier: Modifier = Modifier) {
    val p = ExamesTheme.palette
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, p.decorativeBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(p.infoContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stepNumber.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = p.onInfoContainer,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ExamesInfoBox(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = ExamesTheme.palette
    Surface(
        color = p.infoContainer,
        contentColor = p.onInfoContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onInfoContainer)
            }
            content()
        }
    }
}

@Composable
fun ExamesAlertBox(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    Surface(
        color = p.alertContainer,
        contentColor = p.onAlertContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, p.alertBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                ExamesIcons.WarningTriangle,
                contentDescription = null,
                tint = p.onAlertContainer,
                modifier = Modifier.padding(top = 2.dp).size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.onAlertContainer)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = p.onAlertContainer)
            }
        }
    }
}

@Composable
fun StatusChip(label: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val p = ExamesTheme.palette
    val (bg, fg) = when (kind) {
        ChipKind.INFO -> p.infoContainer to p.onInfoContainer
        ChipKind.REVIEW -> p.reviewContainer to p.onReviewContainer
        ChipKind.ALERT -> p.alertContainer to p.onAlertContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(999.dp), modifier = modifier) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick,
        modifier.heightIn(min = 56.dp).fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick,
        modifier.heightIn(min = 52.dp).fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, ExamesTheme.palette.controlBorder),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ExamesTheme.palette.title)
    }
}

data class BottomDestination(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun ExamesBottomBar(destinations: List<BottomDestination>, selectedRoute: String?, onSelect: (String) -> Unit) {
    Column {
        HorizontalDivider(color = ExamesTheme.palette.decorativeBorder)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            destinations.forEach { d ->
                NavigationBarItem(
                    selected = d.route == selectedRoute,
                    onClick = { onSelect(d.route) },
                    icon = d.icon,
                    label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = d.label },
                )
            }
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
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = ExamesTheme.palette.title,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/** Text button whose layout height (not only its touch slop) is at least 48 dp. */
@Composable
fun ExamesTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = MinTouchTarget), enabled = enabled, content = content)
}
