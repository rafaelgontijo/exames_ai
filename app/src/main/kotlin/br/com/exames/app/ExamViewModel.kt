package br.com.exames.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.coreui.screens.*
import br.com.exames.datalocal.EncryptedExamHistoryStore
import br.com.exames.datalocal.ExamHistoryEntry
import br.com.exames.datalocal.ExamInputException
import br.com.exames.datalocal.ExamInputIssue
import br.com.exames.domain.extraction.AssessedExam
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamSummaryProjection
import br.com.exames.domain.inference.CloudFallbackPolicy
import br.com.exames.domain.inference.InferenceExecutionTarget
import br.com.exames.domain.inference.InferenceRoutingDecisionEngine
import br.com.exames.domain.inference.LocalWeightsState
import br.com.exames.domain.inference.RoutingDecision
import br.com.exames.domain.inference.RoutingReason
import br.com.exames.inferencecloud.CloudAuthTokenProvider
import br.com.exames.inferencecloud.CloudFallbackConfig
import br.com.exames.inferencecloud.CloudFallbackInference
import br.com.exames.inferencelocal.AndroidDeviceCapabilityProbe
import br.com.exames.inferencelocal.BackgroundNotifications
import br.com.exames.inferencelocal.LocalModelStore
import br.com.exames.inferencelocal.LocalTextInference
import br.com.exames.inferencelocal.ModelDownloadPhase
import br.com.exames.inferencelocal.ModelDownloadProgress
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface AppNavEvent {
    data class OpenProcessing(val examId: String) : AppNavEvent
    data class OpenResults(val examId: String) : AppNavEvent
    data object OpenHelp : AppNavEvent
    data object BackToHome : AppNavEvent
}

internal data class OpenedExamState(
    val entry: ExamHistoryEntry,
    val extraction: ExamExtraction,
    val assessed: AssessedExam,
    val filter: ResultFilterOption = ResultFilterOption.ALL,
)

internal data class ExamAppState(
    val loading: Boolean = true,
    val history: List<ExamHistoryEntry> = emptyList(),
    val activeExamId: String? = null,
    val processing: ProcessingUiState? = null,
    val openedExam: OpenedExamState? = null,
    val sessionContext: ClinicalContextUi = ClinicalContextUi(),
    val hybridSettings: HybridSettingsUi = HybridSettingsUi(),
    val pendingCloudConsent: CloudConsentPromptUi? = null,
    val selectedEvidenceId: String? = null,
    val notice: String? = null,
    val navEvent: AppNavEvent? = null,
) {
    val historyCards: List<ExamCardItemUi> get() = history.map(ExamUiMapper::toHistoryCard)

    fun resultsUi(): ResultsUiState? {
        val current = openedExam ?: return null
        return ExamUiMapper.toResultsUi(
            entry = current.entry,
            assessed = current.assessed,
            activeFilter = current.filter,
            notice = notice,
            clinicalContext = sessionContext,
        )
    }

    fun evidenceUi(): EvidenceUiState? {
        val current = openedExam ?: return null
        val targetId = selectedEvidenceId ?: current.assessed.firstReviewObservation()?.observation?.sourceResult?.id ?: return null
        val match = current.assessed.allObservations().firstOrNull { it.observation.sourceResult.id == targetId } ?: return null
        return ExamUiMapper.toEvidenceUi(current.entry.id, current.assessed.pages.size, match)
    }
}

internal class ExamViewModel(application: Application) : AndroidViewModel(application) {
    private val store = EncryptedExamHistoryStore(application)
    private val localInference = LocalTextInference(application)
    private val authTokenProvider = CloudAuthTokenProvider(
        staticBearerToken = BuildConfig.CLOUD_AUTH_TOKEN,
        cognitoRegion = BuildConfig.COGNITO_REGION,
        cognitoClientId = BuildConfig.COGNITO_CLIENT_ID,
        cognitoRefreshToken = BuildConfig.COGNITO_REFRESH_TOKEN,
    )
    private val cloudConfig = CloudFallbackConfig(
        endpointUrl = BuildConfig.CLOUD_FALLBACK_URL,
        authTokenProvider = authTokenProvider::getToken,
    )
    private val cloudInference = CloudFallbackInference(cloudConfig)
    private val capabilityProbe = AndroidDeviceCapabilityProbe(application)
    private val modelStore = LocalModelStore(application)
    private val extractionScheduler = ExamBackgroundScheduler(application)
    private val preferences: HybridPreferences = AppPreferencesStore(application)

    private val mutableState = MutableStateFlow(ExamAppState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var latestDownloadProgress = ModelDownloadProgress()
    private var lastHandledWorkId: UUID? = null
    private var activeSessionWork: Boolean = false
    private var lastEnqueuedExamId: String? = null
    private var lastEnqueuedUseCloud: Boolean = false
    // Kept strictly in RAM until the user accepts or declines cloud opt-in; never persisted.
    private var pendingConsentUri: Uri? = null

    init {
        BackgroundNotifications.ensureChannels(application)
        viewModelScope.launch {
            refreshHistory(initialLoad = true)
            refreshHybridSettings()
        }
        viewModelScope.launch {
            modelStore.observeDownloadProgress().collect { progress ->
                latestDownloadProgress = progress
                refreshHybridSettings()
            }
        }
        viewModelScope.launch {
            extractionScheduler.observe().collect(::onExtractionWorkInfo)
        }
    }

    fun consumeNavEvent() {
        mutableState.update { it.copy(navEvent = null) }
    }

    fun clearNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    fun selectPdf(uri: Uri?) {
        if (uri == null || work?.isActive == true || mutableState.value.activeExamId != null) return
        val decision = evaluateRoutingDecision()
        when (decision.target) {
            InferenceExecutionTarget.LOCAL -> startExtraction(uri, decision)
            InferenceExecutionTarget.CLOUD_FALLBACK -> startExtraction(uri, decision)
            InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED -> {
                pendingConsentUri = uri
                mutableState.update {
                    it.copy(
                        pendingCloudConsent = CloudConsentPromptUi(
                            reasonSummary = routingReasonSummary(decision.reason),
                            canDownloadLocal = decision.canDownloadLocal && BuildConfig.MODEL_DOWNLOAD_URL.isNotBlank(),
                        ),
                    )
                }
            }
            InferenceExecutionTarget.BLOCKED_OFFLINE -> {
                // Keep validation/technical behavior where LocalTextInference runs directly if weights are bundled/mocked
                // or report clear operational reason without making network calls.
                if (decision.reason == RoutingReason.CLOUD_ENDPOINT_UNCONFIGURED && modelStore.hasBundledAsset()) {
                    startExtraction(uri, decision.copy(target = InferenceExecutionTarget.LOCAL))
                } else {
                    mutableState.update { it.copy(notice = blockedOfflineNotice(decision.reason)) }
                }
            }
        }
    }

    fun authorizeCloudFallbackAndContinue() {
        val pending = pendingConsentUri
        pendingConsentUri = null
        preferences.cloudFallbackOptIn = true
        mutableState.update { it.copy(pendingCloudConsent = null) }
        refreshHybridSettings()
        if (pending != null) {
            selectPdf(pending)
        }
    }

    fun declineCloudFallback() {
        pendingConsentUri = null
        preferences.cloudFallbackOptIn = false
        refreshHybridSettings()
        mutableState.update {
            it.copy(
                pendingCloudConsent = null,
                notice = "Modo offline mantido. Nenhum dado do exame foi enviado pela internet.",
            )
        }
    }

    fun setCloudFallbackOptIn(enabled: Boolean) {
        preferences.cloudFallbackOptIn = enabled
        refreshHybridSettings()
    }

    fun setDownloadOnWifiOnly(wifiOnly: Boolean) {
        preferences.downloadOnWifiOnly = wifiOnly
        refreshHybridSettings()
    }

    fun startLocalModelDownload() {
        val url = BuildConfig.MODEL_DOWNLOAD_URL
        if (url.isBlank()) {
            mutableState.update { it.copy(notice = "URL de download do modelo local não configurada nesta compilação.") }
            return
        }
        pendingConsentUri = null
        mutableState.update { it.copy(pendingCloudConsent = null) }
        modelStore.enqueueDownload(url, preferences.downloadOnWifiOnly)
        refreshHybridSettings()
    }

    fun cancelLocalModelDownload() {
        modelStore.cancelDownload()
        refreshHybridSettings()
    }

    fun deleteLocalModelWeights() {
        viewModelScope.launch {
            modelStore.deleteLocalWeights()
            refreshHybridSettings()
            mutableState.update { it.copy(notice = "Pesos locais removidos do aparelho.") }
        }
    }

    private fun startExtraction(uri: Uri, decision: RoutingDecision) {
        val useCloud = decision.target == InferenceExecutionTarget.CLOUD_FALLBACK
        val execMode = if (useCloud) ExecutionModeUi.CLOUD_PRIVATE_FALLBACK else ExecutionModeUi.LOCAL_OFFLINE

        mutableState.update {
            it.copy(
                notice = null,
                pendingCloudConsent = null,
                processing = ProcessingUiState(
                    message = "Abrindo o PDF",
                    cancellable = true,
                    executionMode = execMode,
                ),
            )
        }
        work = viewModelScope.launch {
            try {
                val id = store.accept(uri)
                activeSessionWork = true
                lastEnqueuedExamId = id
                lastEnqueuedUseCloud = useCloud
                mutableState.update {
                    it.copy(
                        activeExamId = id,
                        processing = ProcessingUiState(
                            message = "Analisando laudo em PDF",
                            cancellable = true,
                            executionMode = execMode,
                        ),
                        navEvent = AppNavEvent.OpenProcessing(id),
                    )
                }
                extractionScheduler.enqueue(examId = id, useCloud = useCloud)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ExamInputException) {
                val notice = when (failure.issue) {
                    ExamInputIssue.NOT_PDF -> "Selecione um arquivo PDF."
                    ExamInputIssue.EMPTY -> "O arquivo selecionado está vazio."
                    ExamInputIssue.TOO_LARGE -> "O arquivo excede o limite de tamanho."
                    ExamInputIssue.UNREADABLE -> "Não foi possível ler o arquivo selecionado."
                }
                mutableState.update {
                    it.copy(activeExamId = null, processing = null, notice = notice)
                }
            } catch (failure: Exception) {
                val notice = when (failure.message) {
                    "PDF_PAGE_LIMIT" -> "O PDF excede o limite de páginas."
                    "PDF_OPEN_FAILED" -> "Não foi possível abrir o PDF. Verifique se ele exige senha ou está danificado."
                    else -> "A extração foi interrompida por uma falha no arquivo ou no armazenamento."
                }
                val history = runCatching { store.list() }.getOrElse { mutableState.value.history }
                mutableState.update {
                    it.copy(
                        history = history,
                        activeExamId = null,
                        processing = null,
                        notice = notice,
                    )
                }
            }
        }
    }

    private suspend fun onExtractionWorkInfo(info: WorkInfo?) {
        if (info == null) return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                val workExamId = info.progress.getString(ExamWorkCodec.KEY_EXAM_ID)
                    ?.takeIf { AppRoutes.isOpaqueId(it) }
                    ?: lastEnqueuedExamId
                    ?: return
                val ui = ExamWorkCodec.toProcessingUi(info.progress, fallbackUseCloud = lastEnqueuedUseCloud)
                val shouldOpenProcessing = mutableState.value.activeExamId == null
                activeSessionWork = true
                lastEnqueuedExamId = workExamId
                mutableState.update { current ->
                    if (current.processing?.cancellable == false) return@update current
                    current.copy(
                        activeExamId = workExamId,
                        processing = ui,
                        navEvent = if (shouldOpenProcessing) AppNavEvent.OpenProcessing(workExamId) else current.navEvent,
                    )
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                if (lastHandledWorkId == info.id) return
                lastHandledWorkId = info.id
                val finishedExamId = info.outputData.getString(ExamWorkCodec.KEY_EXAM_ID)
                    ?.takeIf { AppRoutes.isOpaqueId(it) }
                    ?: lastEnqueuedExamId
                val wasWatching = activeSessionWork || mutableState.value.activeExamId != null || mutableState.value.processing != null
                activeSessionWork = false
                lastEnqueuedExamId = null
                val history = runCatching { store.list() }.getOrElse { mutableState.value.history }
                mutableState.update {
                    it.copy(
                        history = history,
                        activeExamId = null,
                        processing = null,
                    )
                }
                if (wasWatching && finishedExamId != null) {
                    BackgroundNotifications.cancel(getApplication(), BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT)
                    openExam(finishedExamId)
                }
            }
            WorkInfo.State.CANCELLED -> {
                if (lastHandledWorkId == info.id) return
                lastHandledWorkId = info.id
                val wasWatching = activeSessionWork || mutableState.value.activeExamId != null || mutableState.value.processing != null
                activeSessionWork = false
                lastEnqueuedExamId = null
                if (wasWatching) {
                    val history = runCatching { store.list() }.getOrElse { emptyList() }
                    mutableState.update {
                        it.copy(
                            history = history,
                            activeExamId = null,
                            processing = null,
                            sessionContext = ClinicalContextUi(),
                            notice = "Extração cancelada.",
                            navEvent = AppNavEvent.BackToHome,
                        )
                    }
                }
            }
            WorkInfo.State.FAILED -> {
                if (lastHandledWorkId == info.id) return
                lastHandledWorkId = info.id
                val wasWatching = activeSessionWork || mutableState.value.activeExamId != null || mutableState.value.processing != null
                activeSessionWork = false
                lastEnqueuedExamId = null
                val history = runCatching { store.list() }.getOrElse { mutableState.value.history }
                if (wasWatching) {
                    val code = info.outputData.getString(ExamWorkCodec.KEY_ERROR_CODE)
                    mutableState.update {
                        it.copy(
                            history = history,
                            activeExamId = null,
                            processing = null,
                            notice = ExamWorkCodec.errorNotice(code),
                            navEvent = AppNavEvent.BackToHome,
                        )
                    }
                } else {
                    mutableState.update { it.copy(history = history) }
                }
            }
        }
    }

    fun handleNotificationIntent(examId: String?, destination: String?) {
        if (!examId.isNullOrBlank() && AppRoutes.isOpaqueId(examId)) {
            BackgroundNotifications.cancel(getApplication(), BackgroundNotifications.NOTIFICATION_ID_EXAM_RESULT)
            openExam(examId)
            return
        }
        when (destination) {
            BackgroundNotifications.DESTINATION_HELP -> {
                BackgroundNotifications.cancel(getApplication(), BackgroundNotifications.NOTIFICATION_ID_MODEL_RESULT)
                mutableState.update { it.copy(navEvent = AppNavEvent.OpenHelp) }
            }
            BackgroundNotifications.DESTINATION_PROCESSING -> {
                val active = mutableState.value.activeExamId
                if (active != null) {
                    mutableState.update { it.copy(navEvent = AppNavEvent.OpenProcessing(active)) }
                }
            }
        }
    }

    private fun evaluateRoutingDecision(): RoutingDecision {
        val device = capabilityProbe.snapshot()
        val isDownloading = latestDownloadProgress.phase == ModelDownloadPhase.DOWNLOADING ||
            latestDownloadProgress.phase == ModelDownloadPhase.QUEUED ||
            latestDownloadProgress.phase == ModelDownloadPhase.VERIFYING
        val weights = modelStore.inspectState(isDownloading = isDownloading)
        val policy = CloudFallbackPolicy(
            cloudFallbackOptIn = preferences.cloudFallbackOptIn,
            cloudEndpointConfigured = cloudConfig.isConfigured,
            networkAvailable = capabilityProbe.isNetworkAvailable(),
        )
        return InferenceRoutingDecisionEngine.decide(device, weights, policy)
    }

    private fun refreshHybridSettings() {
        val decision = evaluateRoutingDecision()
        val progress = latestDownloadProgress
        val isDownloading = progress.phase == ModelDownloadPhase.DOWNLOADING ||
            progress.phase == ModelDownloadPhase.QUEUED ||
            progress.phase == ModelDownloadPhase.VERIFYING
        val weightsState = modelStore.inspectState(isDownloading = isDownloading)
        val uiStatus = when {
            !decision.hardwareEligibleForLocal && !decision.canDownloadLocal -> LocalModelStatusUi.INELIGIBLE_HARDWARE
            isDownloading -> LocalModelStatusUi.DOWNLOADING
            weightsState == LocalWeightsState.READY -> LocalModelStatusUi.READY
            weightsState == LocalWeightsState.INSUFFICIENT_SPACE || progress.phase == ModelDownloadPhase.FAILED_SPACE ->
                LocalModelStatusUi.INSUFFICIENT_SPACE
            weightsState == LocalWeightsState.CORRUPT ||
                progress.phase == ModelDownloadPhase.FAILED_INTEGRITY ||
                progress.phase == ModelDownloadPhase.FAILED_NETWORK -> LocalModelStatusUi.ERROR
            else -> LocalModelStatusUi.NOT_DOWNLOADED
        }
        val activeLabel = when (decision.target) {
            InferenceExecutionTarget.LOCAL -> "IA local no aparelho (offline)"
            InferenceExecutionTarget.CLOUD_FALLBACK -> "Fallback privado na nuvem (opt-in)"
            InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED -> "Modo offline · requer consentimento para nuvem"
            InferenceExecutionTarget.BLOCKED_OFFLINE -> "Modo estritamente offline"
        }
        val statusMsg = when (uiStatus) {
            LocalModelStatusUi.READY -> "Pesos locais do Gemma 4 E2B verificados por SHA-256 prontos neste aparelho."
            LocalModelStatusUi.DOWNLOADING -> "Baixando pesos locais do Gemma 4 E2B em segundo plano..."
            LocalModelStatusUi.INELIGIBLE_HARDWARE -> "Este aparelho não atende ao mínimo de hardware local (≥ 6 GB RAM e GPU 64-bit)."
            LocalModelStatusUi.INSUFFICIENT_SPACE -> "Espaço livre insuficiente para os pesos locais (necessário ~2,65 GB)."
            LocalModelStatusUi.ERROR -> "O download ou verificação de integridade dos pesos falhou. Tente novamente."
            LocalModelStatusUi.NOT_DOWNLOADED -> "Pesos locais ainda não baixados (APK leve)."
        }
        mutableState.update {
            it.copy(
                hybridSettings = HybridSettingsUi(
                    activeModeLabel = activeLabel,
                    isCloudActive = decision.target == InferenceExecutionTarget.CLOUD_FALLBACK,
                    localModelStatus = uiStatus,
                    downloadFraction = progress.fraction,
                    downloadStatusMessage = statusMsg,
                    canDownloadLocal = decision.canDownloadLocal && BuildConfig.MODEL_DOWNLOAD_URL.isNotBlank(),
                    canRemoveLocal = weightsState == LocalWeightsState.READY && !modelStore.hasBundledAsset(),
                    cloudFallbackOptIn = preferences.cloudFallbackOptIn,
                    downloadOnWifiOnly = preferences.downloadOnWifiOnly,
                    cloudEndpointConfigured = cloudConfig.isConfigured,
                ),
            )
        }
    }

    private fun routingReasonSummary(reason: RoutingReason): String = when (reason) {
        RoutingReason.HARDWARE_NOT_64BIT,
        RoutingReason.HARDWARE_NO_GPU,
        RoutingReason.HARDWARE_RAM_BELOW_MINIMUM ->
            "Este aparelho não atende aos requisitos mínimos de hardware para executar a IA localmente (≥ 6 GB de RAM e GPU 64-bit)."
        RoutingReason.HARDWARE_AVAILABLE_RAM_LOW ->
            "A memória RAM livre no momento está abaixo do mínimo seguro para executar a IA localmente."
        RoutingReason.WEIGHTS_DOWNLOADING ->
            "Os pesos locais da IA ainda estão sendo baixados em segundo plano."
        RoutingReason.WEIGHTS_INSUFFICIENT_SPACE ->
            "Não há espaço livre suficiente no aparelho para armazenar os pesos locais (~2,65 GB)."
        RoutingReason.WEIGHTS_CORRUPT ->
            "Os pesos locais falharam na verificação de integridade SHA-256."
        else ->
            "Os pesos locais da IA ainda não foram baixados neste aparelho."
    }

    private fun blockedOfflineNotice(reason: RoutingReason): String = when (reason) {
        RoutingReason.CLOUD_NETWORK_UNAVAILABLE ->
            "Sem conexão com a internet para o fallback privado na nuvem."
        RoutingReason.WEIGHTS_INSUFFICIENT_SPACE ->
            "Espaço livre insuficiente para preparar o modelo local."
        RoutingReason.HARDWARE_RAM_BELOW_MINIMUM,
        RoutingReason.HARDWARE_AVAILABLE_RAM_LOW,
        RoutingReason.HARDWARE_NO_GPU,
        RoutingReason.HARDWARE_NOT_64BIT ->
            "Este aparelho não suporta inferência local e o fallback em nuvem não está disponível no momento."
        else ->
            "Modelo local ainda não baixado. Baixe os pesos locais em Ajuda ou habilite o fallback em nuvem."
    }

    fun cancelProcessing() {
        val current = mutableState.value
        if (current.processing?.cancellable != true) return
        val activeJob = work
        val activeId = current.activeExamId
        mutableState.update {
            it.copy(processing = ProcessingUiState(message = "Cancelando a extração", cancellable = false))
        }
        extractionScheduler.cancel()
        work = viewModelScope.launch {
            activeJob?.cancelAndJoin()
            if (activeId != null) {
                runCatching { store.cancelActive(activeId) }
            }
        }
    }

    fun openExam(examId: String) {
        if (work?.isActive == true) return
        viewModelScope.launch {
            val record = runCatching { store.load(examId) }.getOrNull()
            val extraction = record?.extraction
            when {
                record == null -> {
                    refreshHistory(notice = "Não foi possível reabrir o exame selecionado.")
                }
                extraction == null -> {
                    mutableState.update {
                        it.copy(notice = "A extração deste exame foi interrompida. Você pode excluí-lo ou importar novamente.")
                    }
                }
                else -> {
                    val domainContext = ExamUiMapper.toDomainContext(mutableState.value.sessionContext)
                    val assessed = ExamSummaryProjection.project(extraction, domainContext)
                    mutableState.update {
                        it.copy(
                            openedExam = OpenedExamState(record.entry, extraction, assessed),
                            selectedEvidenceId = null,
                            notice = null,
                            navEvent = AppNavEvent.OpenResults(examId),
                        )
                    }
                }
            }
        }
    }

    fun selectFilter(option: ResultFilterOption) {
        mutableState.update { current ->
            val opened = current.openedExam ?: return@update current
            current.copy(openedExam = opened.copy(filter = option))
        }
    }

    /** Updates ephemeral session-only clinical context strictly in RAM; never saved to storage. */
    fun updateClinicalContext(contextUi: ClinicalContextUi) {
        mutableState.update { current ->
            val opened = current.openedExam
            val updatedOpened = if (opened != null) {
                val domainContext = ExamUiMapper.toDomainContext(contextUi)
                opened.copy(assessed = ExamSummaryProjection.project(opened.extraction, domainContext))
            } else {
                null
            }
            current.copy(
                sessionContext = contextUi,
                openedExam = updatedOpened,
            )
        }
    }

    fun clearClinicalContext() {
        updateClinicalContext(ClinicalContextUi())
    }

    fun selectEvidence(observationId: String) {
        mutableState.update { it.copy(selectedEvidenceId = observationId) }
    }

    fun deleteExam(examId: String) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            val removed = runCatching { store.delete(examId) }.isSuccess
            val history = runCatching { store.list() }.getOrElse { emptyList() }
            mutableState.update { current ->
                val wasOpened = current.openedExam?.entry?.id == examId
                current.copy(
                    history = history,
                    openedExam = if (wasOpened) null else current.openedExam,
                    sessionContext = if (wasOpened) ClinicalContextUi() else current.sessionContext,
                    selectedEvidenceId = if (wasOpened) null else current.selectedEvidenceId,
                    notice = if (removed) "Extração descartada." else "Não foi possível descartar. Tente novamente.",
                    navEvent = if (wasOpened) AppNavEvent.BackToHome else null,
                )
            }
        }
    }

    fun deleteAllExams() {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            val removed = runCatching { store.deleteAll() }.isSuccess
            val history = runCatching { store.list() }.getOrElse { emptyList() }
            mutableState.update {
                it.copy(
                    history = history,
                    openedExam = null,
                    sessionContext = ClinicalContextUi(),
                    selectedEvidenceId = null,
                    notice = if (removed) "Todos os exames foram apagados." else "Não foi possível apagar os exames.",
                    navEvent = AppNavEvent.BackToHome,
                )
            }
        }
    }

    suspend fun preview(examId: String, page: Int): Bitmap {
        var allocated: Bitmap? = null
        try {
            val pdf = store.pdf.open(examId)
            try {
                return withContext(Dispatchers.IO) {
                    pdf.readPage(page).use { snapshot ->
                        checkNotNull(snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, false)).also { allocated = it }
                    }
                }
            } finally {
                pdf.closeAsync()
            }
        } catch (failure: Throwable) {
            allocated?.recycle()
            throw failure
        }
    }

    private suspend fun refreshHistory(initialLoad: Boolean = false, notice: String? = null) {
        val loaded = runCatching { store.list() }
        mutableState.update { current ->
            if (loaded.isSuccess) {
                current.copy(
                    loading = false,
                    history = loaded.getOrThrow(),
                    notice = notice ?: current.notice,
                )
            } else {
                current.copy(
                    loading = false,
                    notice = notice ?: if (initialLoad) "Não foi possível abrir o histórico local." else current.notice,
                )
            }
        }
    }
}
