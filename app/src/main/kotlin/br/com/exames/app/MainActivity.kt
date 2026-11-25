package br.com.exames.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import br.com.exames.inferencelocal.BackgroundNotifications
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.exames.coreui.*
import br.com.exames.coreui.screens.*

class MainActivity : ComponentActivity() {
    private val exam: ExamViewModel by viewModels()
    private var askedNotificationsThisSession = false
    private var pendingAfterNotificationPrompt: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Grant or denial never blocks exam extraction or model download.
        val next = pendingAfterNotificationPrompt
        pendingAfterNotificationPrompt = null
        next?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handleNotificationIntent(intent)
        setContent {
            val state by exam.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), exam::selectPdf)
            ExamesTheme {
                ExamesAppShell(
                    state = state,
                    onSelectPdf = {
                        runWithOptionalNotificationPermission {
                            picker.launch(arrayOf("application/pdf"))
                        }
                    },
                    onOpenExam = exam::openExam,
                    onSelectFilter = exam::selectFilter,
                    onUpdateContext = exam::updateClinicalContext,
                    onClearContext = exam::clearClinicalContext,
                    onSelectEvidence = exam::selectEvidence,
                    onCancelProcessing = exam::cancelProcessing,
                    onDeleteExam = exam::deleteExam,
                    onDeleteAllExams = exam::deleteAllExams,
                    onConsumeNavEvent = exam::consumeNavEvent,
                    onPreviewPage = exam::preview,
                    onAuthorizeCloudFallback = exam::authorizeCloudFallbackAndContinue,
                    onDeclineCloudFallback = exam::declineCloudFallback,
                    onToggleCloudFallback = exam::setCloudFallbackOptIn,
                    onToggleWifiOnly = exam::setDownloadOnWifiOnly,
                    onStartModelDownload = {
                        runWithOptionalNotificationPermission {
                            exam.startLocalModelDownload()
                        }
                    },
                    onCancelModelDownload = exam::cancelLocalModelDownload,
                    onDeleteLocalModel = exam::deleteLocalModelWeights,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(source: Intent?) {
        if (source == null) return
        val examId = source.getStringExtra(BackgroundNotifications.EXTRA_OPEN_EXAM_ID)
        val destination = source.getStringExtra(BackgroundNotifications.EXTRA_OPEN_DESTINATION)
        if (!examId.isNullOrBlank() || !destination.isNullOrBlank()) {
            source.removeExtra(BackgroundNotifications.EXTRA_OPEN_EXAM_ID)
            source.removeExtra(BackgroundNotifications.EXTRA_OPEN_DESTINATION)
            exam.handleNotificationIntent(examId, destination)
        }
    }

    private fun runWithOptionalNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedNotificationsThisSession) {
            action()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
            return
        }
        askedNotificationsThisSession = true
        pendingAfterNotificationPrompt = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
internal fun ExamesAppShell(
    state: ExamAppState,
    onSelectPdf: () -> Unit,
    onOpenExam: (String) -> Unit,
    onSelectFilter: (ResultFilterOption) -> Unit,
    onUpdateContext: (ClinicalContextUi) -> Unit,
    onClearContext: () -> Unit,
    onSelectEvidence: (String) -> Unit,
    onCancelProcessing: () -> Unit,
    onDeleteExam: (String) -> Unit,
    onDeleteAllExams: () -> Unit,
    onConsumeNavEvent: () -> Unit,
    onPreviewPage: suspend (String, Int) -> android.graphics.Bitmap,
    onAuthorizeCloudFallback: () -> Unit = {},
    onDeclineCloudFallback: () -> Unit = {},
    onToggleCloudFallback: (Boolean) -> Unit = {},
    onToggleWifiOnly: (Boolean) -> Unit = {},
    onStartModelDownload: () -> Unit = {},
    onCancelModelDownload: () -> Unit = {},
    onDeleteLocalModel: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Wipe ephemeral session-only clinical context when leaving a finished Results view back to top-level tabs.
    // Keep it intact while filling context before import (Home/Send -> Processing -> Results -> Evidence -> Results).
    var previousRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoute) {
        if (previousRoute == AppRoutes.RESULTS && currentRoute in AppRoutes.bottomTabRoutes) {
            onClearContext()
        }
        previousRoute = currentRoute
    }

    LaunchedEffect(state.navEvent) {
        val event = state.navEvent ?: return@LaunchedEffect
        onConsumeNavEvent()
        when (event) {
            is AppNavEvent.OpenProcessing -> {
                navController.navigate(AppRoutes.processing(event.examId)) {
                    launchSingleTop = true
                }
            }
            is AppNavEvent.OpenResults -> {
                navController.navigate(AppRoutes.results(event.examId)) {
                    popUpTo(AppRoutes.HOME) { inclusive = false }
                    launchSingleTop = true
                }
            }
            AppNavEvent.OpenHelp -> {
                navController.navigate(AppRoutes.HELP) {
                    popUpTo(AppRoutes.HOME) { inclusive = false }
                    launchSingleTop = true
                }
            }
            AppNavEvent.BackToHome -> {
                navController.popBackStack(AppRoutes.HOME, inclusive = false)
            }
        }
    }

    val destinations = listOf(
        BottomDestination(AppRoutes.HOME, "Início") { Icon(ExamesIcons.Home, contentDescription = null) },
        BottomDestination(AppRoutes.EXAMS, "Exames") { Icon(ExamesIcons.Document, contentDescription = null) },
        BottomDestination(AppRoutes.HELP, "Ajuda") { Icon(ExamesIcons.ShieldLock, contentDescription = null) },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in AppRoutes.bottomTabRoutes) {
                ExamesBottomBar(
                    destinations = destinations,
                    selectedRoute = currentRoute,
                    onSelect = { target ->
                        navController.navigate(target) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = AppRoutes.HOME,
            ) {
                composable(AppRoutes.HOME) {
                    HomeScreen(
                        recentExams = state.historyCards,
                        notice = state.notice,
                        onSelectPdf = onSelectPdf,
                        onOpenSend = { navController.navigate(AppRoutes.SEND) },
                        onOpenExam = onOpenExam,
                        onOpenHistory = {
                            navController.navigate(AppRoutes.EXAMS) {
                                popUpTo(AppRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onOpenHelp = {
                            navController.navigate(AppRoutes.HELP) {
                                popUpTo(AppRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onDeleteExam = onDeleteExam,
                        clinicalContext = state.sessionContext,
                        onUpdateContext = onUpdateContext,
                        onClearContext = onClearContext,
                        executionModeLabel = state.hybridSettings.activeModeLabel,
                    )
                }

                composable(AppRoutes.EXAMS) {
                    ExamsHistoryScreen(
                        exams = state.historyCards,
                        notice = state.notice,
                        onOpenExam = onOpenExam,
                        onDeleteExam = onDeleteExam,
                        onDeleteAll = onDeleteAllExams,
                        onOpenSend = { navController.navigate(AppRoutes.SEND) },
                    )
                }

                composable(AppRoutes.HELP) {
                    HelpScreen(
                        onBack = null,
                        onOpenExams = {
                            navController.navigate(AppRoutes.EXAMS) {
                                popUpTo(AppRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        hybridSettings = state.hybridSettings,
                        onToggleCloudFallback = onToggleCloudFallback,
                        onToggleWifiOnly = onToggleWifiOnly,
                        onStartDownload = onStartModelDownload,
                        onCancelDownload = onCancelModelDownload,
                        onDeleteLocalModel = onDeleteLocalModel,
                    )
                }

                composable(AppRoutes.HELP_REVIEW) {
                    HelpScreen(
                        onBack = { navController.popBackStack() },
                        onOpenExams = { navController.popBackStack() },
                        primaryActionLabel = "Voltar aos resultados",
                        hybridSettings = state.hybridSettings,
                        onToggleCloudFallback = onToggleCloudFallback,
                        onToggleWifiOnly = onToggleWifiOnly,
                        onStartDownload = onStartModelDownload,
                        onCancelDownload = onCancelModelDownload,
                        onDeleteLocalModel = onDeleteLocalModel,
                    )
                }

                composable(AppRoutes.SEND) {
                    SendExamScreen(
                        notice = state.notice,
                        onBack = { navController.popBackStack() },
                        onSelectPdf = onSelectPdf,
                        onOpenHistory = {
                            navController.navigate(AppRoutes.EXAMS) {
                                popUpTo(AppRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onOpenHelp = { navController.navigate(AppRoutes.HELP_REVIEW) },
                        clinicalContext = state.sessionContext,
                        onUpdateContext = onUpdateContext,
                        onClearContext = onClearContext,
                    )
                }

                composable(
                    route = AppRoutes.PROCESSING,
                    arguments = listOf(navArgument("examId") { type = NavType.StringType }),
                ) {
                    val processing = state.processing ?: ProcessingUiState(message = "Preparando leitura local")
                    ProcessingScreen(
                        state = processing,
                        onCancel = onCancelProcessing,
                    )
                }

                composable(
                    route = AppRoutes.RESULTS,
                    arguments = listOf(navArgument("examId") { type = NavType.StringType }),
                ) { entry ->
                    val examId = entry.arguments?.getString("examId")
                    LaunchedEffect(examId, state.openedExam?.entry?.id) {
                        if (examId != null && AppRoutes.isOpaqueId(examId) && state.openedExam?.entry?.id != examId) {
                            onOpenExam(examId)
                        }
                    }
                    val ui = state.resultsUi()
                    if (ui != null) {
                        ResultsScreen(
                            state = ui,
                            onBack = {
                                onClearContext()
                                navController.popBackStack(AppRoutes.HOME, inclusive = false)
                            },
                            onSelectFilter = onSelectFilter,
                            onUpdateContext = onUpdateContext,
                            onClearContext = onClearContext,
                            onOpenEvidence = { obsId ->
                                onSelectEvidence(obsId)
                                navController.navigate(AppRoutes.evidence(ui.examId))
                            },
                            onOpenHelp = { navController.navigate(AppRoutes.HELP_REVIEW) },
                            onSelectAnotherPdf = onSelectPdf,
                            onDeleteExam = { onDeleteExam(ui.examId) },
                        )
                    } else {
                        ProcessingScreen(
                            state = ProcessingUiState(message = "Carregando exame", cancellable = false),
                            onCancel = {},
                        )
                    }
                }

                composable(
                    route = AppRoutes.EVIDENCE,
                    arguments = listOf(navArgument("examId") { type = NavType.StringType }),
                ) { entry ->
                    val examId = entry.arguments?.getString("examId")
                    val ui = state.evidenceUi()
                    if (ui != null && examId != null) {
                        EvidenceScreen(
                            state = ui,
                            loadPage = { page -> onPreviewPage(examId, page) },
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        LaunchedEffect(examId) {
                            navController.popBackStack()
                        }
                    }
                }
            }
        }
    }

    state.pendingCloudConsent?.let { prompt ->
        CloudConsentDialog(
            prompt = prompt,
            onAuthorize = onAuthorizeCloudFallback,
            onDeclineOffline = onDeclineCloudFallback,
            onDownloadLocal = if (prompt.canDownloadLocal) onStartModelDownload else null,
        )
    }
}
