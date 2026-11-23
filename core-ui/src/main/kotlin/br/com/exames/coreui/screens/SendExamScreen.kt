package br.com.exames.coreui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*

/** Screen 2: Enviar exame — Meu Exame. */
@Composable
fun SendExamScreen(
    notice: String?,
    onBack: () -> Unit,
    onSelectPdf: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ExamesTopBar(title = "Enviar exame", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusChip(label = "100% privado no seu aparelho", kind = ChipKind.INFO)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Como você quer enviar?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = p.title,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Escolha o arquivo do laudo salvo no seu aparelho.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.secondaryText,
                )
            }

            if (notice != null) {
                Surface(
                    color = p.reviewContainer,
                    contentColor = p.onReviewContainer,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, p.reviewBorder),
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(notice, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Primary Source Card
            ExamesHeroCard(
                title = "Selecionar PDF digital",
                subtitle = "Baixado do laboratório, e-mail ou arquivos do celular",
                icon = ExamesIcons.Document,
                onClick = onSelectPdf,
            )

            // Secondary Source Card
            ExamesCard(
                onClick = onOpenHistory,
                borderedControl = true,
                modifier = Modifier.semantics {
                    contentDescription = "Abrir histórico local. Consultar extrações salvas apenas neste aparelho"
                },
            ) {
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
                        Icon(ExamesIcons.Folder, contentDescription = null, tint = p.onInfoContainer, modifier = Modifier.size(26.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Abrir histórico local", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.title)
                        Text("Consultar extrações salvas apenas neste aparelho", style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                    }
                }
            }

            // Documentary Tips Card
            ExamesInfoBox(title = "Dicas para uma leitura fiel") {
                TipRow("Prefira o PDF digital original emitido pelo laboratório")
                TipRow("Todas as páginas são lidas em ordem documental")
                TipRow("Dados ambíguos ficam marcados para conferência")
            }

            // Privacy Notice Card
            ExamesCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        ExamesIcons.ShieldLock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp).size(22.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Processamento 100% offline neste aparelho, com armazenamento privado e cifrado. Nenhum dado do exame é enviado pela internet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.secondaryText,
                        )
                        ExamesTextButton(onClick = onOpenHelp) {
                            Text("Como protegemos seus dados", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TipRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            ExamesIcons.Check,
            contentDescription = null,
            tint = ExamesTheme.palette.onInfoContainer,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ExamesTheme.palette.onInfoContainer)
    }
}
