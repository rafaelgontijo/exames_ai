package br.com.exames.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val state by exam.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), exam::selectPdf)
            ExamesTheme {
                ExamesAppShell(
                    state = state,
                    onSelectPdf = { picker.launch(arrayOf("application/pdf")) },
                    onOpenExam = exam::openExam,
                    onSelectFilter = exam::selectFilter,
                    onSelectEvidence = exam::selectEvidence,
                    onCancelProcessing = exam::cancelProcessing,
                    onDeleteExam = exam::deleteExam,
                    onDeleteAllExams = exam::deleteAllExams,
                    onConsumeNavEvent = exam::consumeNavEvent,
                    onPreviewPage = exam::preview,
                )
            }
        }
    }
}

@Composable
internal fun ExamesAppShell(
    state: ExamAppState,
    onSelectPdf: () -> Unit,
    onOpenExam: (String) -> Unit,
    onSelectFilter: (ResultFilterOption) -> Unit,
    onSelectEvidence: (String) -> Unit,
    onCancelProcessing: () -> Unit,
    onDeleteExam: (String) -> Unit,
    onDeleteAllExams: () -> Unit,
    onConsumeNavEvent: () -> Unit,
    onPreviewPage: suspend (String, Int) -> android.graphics.Bitmap,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

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
                    )
                }

                composable(AppRoutes.HELP_REVIEW) {
                    HelpScreen(
                        onBack = { navController.popBackStack() },
                        onOpenExams = { navController.popBackStack() },
                        primaryActionLabel = "Voltar aos resultados",
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
                            onBack = { navController.popBackStack(AppRoutes.HOME, inclusive = false) },
                            onSelectFilter = onSelectFilter,
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
}
