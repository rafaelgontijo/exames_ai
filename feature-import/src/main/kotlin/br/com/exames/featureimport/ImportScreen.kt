package br.com.exames.featureimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.BitmapFactory
import br.com.exames.datadocuments.CameraXPageCapturer
import br.com.exames.datadocuments.CaptureRejected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import br.com.exames.coreui.*
import br.com.exames.domain.inference.ModelReadiness
import br.com.exames.domain.validation.ExtractionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.exames.domain.validation.RunState

@Composable
fun ImportScreen(onResults: (String) -> Unit, onHistory: () -> Unit, model: ImportViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::select)
    }
    val corpusPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::selectPackage) }
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.onCameraPermission(granted) }
    // The permission is requested only when the user chooses to photograph.
    val photograph = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) model.onCameraPermission(true)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    // Permission revoked while the camera or review is open: discard the candidate.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (model.state.value.capture != CapturePhase.NONE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            model.endCapture("A permissão da câmera foi revogada. A foto foi descartada; você pode selecionar um PDF ou imagem.")
    }
    // Leaving the screen ends the session (not on rotation, where the ViewModel keeps the in-memory candidate).
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) model.endCapture() } }
    ImportContent(state, onPickDocument = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) },
        onPickPackage = { corpusPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
        onSelectMode = model::selectMode, onRecheckModel = model::recheckModel, onCancel = model::cancel,
        onReschedule = model::reschedule, onRetry = model::retry, onResults = onResults, onHistory = onHistory,
        onPhotograph = photograph, onTakePhoto = model::takePhoto, onUsePhoto = model::useCapture, onRetake = model::retake,
        onCancelCapture = model::cancelCapture,
        cameraSlot = { CameraPreview(model) }, reviewSlot = { CapturedPreview(model, state.captureRevision) })
}

/** Stateless so the controls can be exercised without a ViewModel, storage or the model. */
@Composable
fun ImportContent(state: ImportUiState, onPickDocument: () -> Unit, onPickPackage: () -> Unit, onSelectMode: (ExtractionMode) -> Unit,
    onRecheckModel: () -> Unit, onCancel: () -> Unit, onReschedule: () -> Unit, onRetry: () -> Unit,
    onResults: (String) -> Unit, onHistory: () -> Unit,
    onPhotograph: () -> Unit = {}, onTakePhoto: () -> Unit = {}, onUsePhoto: () -> Unit = {}, onRetake: () -> Unit = {},
    onCancelCapture: () -> Unit = {}, cameraSlot: @Composable () -> Unit = {}, reviewSlot: @Composable () -> Unit = {}) {
    ExamesScreen("Enviar exame") {
        ExamesCard {
            Text("Leitura referencial de resultados laboratoriais, no seu dispositivo.", style = MaterialTheme.typography.titleMedium)
            Text("O processamento acontece neste aparelho; nada é enviado para fora. Escolher uma fonte não confirma os valores lidos.")
            Text("Não fornece diagnósticos nem recomendações clínicas.")
            Text("PDF, JPEG ou PNG · até 20 MiB e 20 páginas")
        }
        Text("Como processar?", style = MaterialTheme.typography.titleLarge, color = ExamesTheme.palette.title)
        for(mode in ExtractionMode.entries) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(
            selected=state.mode==mode,enabled=!state.importing && !state.actionBusy && state.modelReadiness!=ModelReadiness.PREPARING,
            role=Role.RadioButton,onClick={ onSelectMode(mode) }),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            RadioButton(selected=state.mode==mode,onClick=null)
            Text(if(mode==ExtractionMode.BASELINE) "Modo sem IA" else "Modo assistido local")
        }
        if(state.mode==ExtractionMode.ASSISTED) {
            Text(when(state.modelReadiness) {
                ModelReadiness.NOT_PREPARED -> "Prepare e verifique o modelo antes de importar."
                ModelReadiness.PREPARING -> "Preparando e verificando o modelo local; pode levar alguns instantes."
                ModelReadiness.READY -> "Modelo preparado nesta sessão. A integridade é verificada novamente em cada inferência."
                ModelReadiness.UNSUPPORTED_DEVICE -> "Modo assistido validado somente no S25 de referência desta versão. O modo sem IA está disponível neste aparelho."
                ModelReadiness.VERIFICATION_PENDING -> "Modelo íntegro preparado; a ativação aguarda o ensaio final do protocolo."
                ModelReadiness.MISSING -> "O modelo não está disponível nesta distribuição. Nenhum download será feito."
                ModelReadiness.CORRUPT -> "A verificação de integridade do modelo falhou."
                ModelReadiness.INSUFFICIENT_SPACE -> "Espaço insuficiente para preparar o modelo. Libere espaço ou use o modo sem IA."
                ModelReadiness.STORAGE_UNAVAILABLE -> "O armazenamento do modelo está indisponível."
            })
            if(state.modelReadiness==ModelReadiness.PREPARING) CircularProgressIndicator()
            else ExamesTextButton(onClick=onRecheckModel,enabled=!state.importing && !state.actionBusy) { Text("Preparar/verificar modelo") }
            Text("O modelo propõe campos da fonte; o avaliador determinístico faz a comparação. Falhas usam o caminho sem IA e são identificadas como fallback.")
        }
        val canImport=!state.importing && !state.actionBusy && state.capture==CapturePhase.NONE &&
            (state.mode==ExtractionMode.BASELINE || state.modelReadiness==ModelReadiness.READY)
        PrimaryAction("Selecionar documento", onPickDocument, enabled = canImport)
        if (state.capture == CapturePhase.NONE) SecondaryAction("Fotografar uma página", onPhotograph, enabled = canImport)
        else CapturePanel(state, onTakePhoto, onUsePhoto, onRetake, onCancelCapture, cameraSlot, reviewSlot)
        SecondaryAction("Importar pacote do corpus", onPickPackage, enabled = canImport)
        Text("Pacote ZIP local: até 128 MiB, manifesto, documentos e gabaritos. Hashes verificam integridade, sem autenticar o autor.")
        if (state.importedCases > 0) Text("Casos importados: ${state.importedCases}. Execuções individuais no histórico.")
        if (state.importing) { CircularProgressIndicator(); Text("Criando cópia privada protegida…") }
        state.runState?.let { status ->
            Text(when (status) {
                RunState.QUEUED -> "Aguardando processamento"
                RunState.RUNNING -> "Processando documento"
                RunState.COMPLETE -> "Processamento concluído"
                RunState.PARTIAL -> "Processamento parcial: há páginas com falha"
                RunState.FAILED -> "Não foi possível processar o documento"
                RunState.CANCELLED -> "Processamento cancelado"
            }, style = MaterialTheme.typography.titleMedium)
            Text("Páginas concluídas: ${state.completedPages} de ${state.totalPages ?: "total ainda desconhecido"}. Falhas: ${state.failedPages}.")
            if (status in setOf(RunState.QUEUED, RunState.RUNNING)) {
                if (status == RunState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                ExamesTextButton(enabled = !state.actionBusy, onClick = onCancel) { Text("Cancelar processamento") }
                if (status == RunState.QUEUED) ExamesTextButton(enabled = !state.actionBusy, onClick = onReschedule) { Text("Tentar agendamento") }
            }
            if (status in setOf(RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED))
                ExamesTextButton(enabled = !state.actionBusy, onClick = onRetry) { Text("Reprocessar em nova execução") }
        }
        state.message?.let { Text(it) }
        state.runId?.let { id -> ExamesTextButton(onClick = { onResults(id) }) { Text("Revisar resultados") } }
        ExamesTextButton(onClick = onHistory, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("Histórico local") }
    }
}

@Composable
internal fun CapturePanel(state: ImportUiState, onTakePhoto: () -> Unit, onUse: () -> Unit, onRetake: () -> Unit, onCancel: () -> Unit,
    cameraSlot: @Composable () -> Unit, reviewSlot: @Composable () -> Unit) {
    ExamesCard(Modifier.testTag("capture-panel")) {
        Text("Fotografar uma página", style = MaterialTheme.typography.titleMedium)
        if (state.capture == CapturePhase.CAMERA) {
            Text("Mostre a folha inteira, sem cortar. Evite sombra e reflexo. Uma foto para cada página.")
            cameraSlot()
            PrimaryAction("Tirar foto", onTakePhoto)
        } else {
            Text("Confira se a página está inteira e legível.")
            reviewSlot()
            Text("Usar a foto apenas escolhe esta imagem como fonte. Os valores lidos continuam sujeitos à revisão.")
            PrimaryAction("Usar esta foto", onUse)
            SecondaryAction("Refazer", onRetake)
        }
        ExamesTextButton(onClick = onCancel) { Text("Cancelar foto") }
    }
}

/** Camera preview bound to this screen's lifecycle; the source is detached and the camera released when it leaves. */
@Composable
internal fun CameraPreview(model: ImportViewModel) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val capturer = remember { CameraXPageCapturer(context) }
    val source = remember { PageCaptureSource { capturer.capture() } }
    var view by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; view = this } },
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).testTag("camera-preview")
            .semantics { contentDescription = "Visor da câmera traseira" })
    LaunchedEffect(view) {
        val previewView = view ?: return@LaunchedEffect
        try { capturer.open(owner, previewView.surfaceProvider); model.attachCaptureSource(source) }
        catch (rejected: CaptureRejected) { model.onCameraFailure(rejected.code) }
    }
    DisposableEffect(Unit) { onDispose { model.detachCaptureSource(source); capturer.close() } }
}

/** Decodes a transient copy of the candidate for display; copies are wiped and nothing is saved or restored. */
@Composable
internal fun CapturedPreview(model: ImportViewModel, revision: Int) {
    val image by produceState<ImageBitmap?>(null, revision) {
        value = withContext(Dispatchers.Default) {
            model.previewBytes()?.let { bytes ->
                try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } finally { bytes.fill(0) }
            }
        }
    }
    image?.let { Image(it, contentDescription = "Prévia da foto capturada, já na orientação em que será salva",
        modifier = Modifier.fillMaxWidth().testTag("capture-preview"), contentScale = ContentScale.Fit) }
        ?: CircularProgressIndicator()
}
