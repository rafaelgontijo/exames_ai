package br.com.exames.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import br.com.exames.coreui.BottomDestination
import br.com.exames.coreui.ExamesBottomBar
import br.com.exames.domain.validation.ProcessingRunIds
import br.com.exames.featurehistory.HistoryScreen
import br.com.exames.featurehistory.HistoryViewModel
import br.com.exames.featurehistory.HomeScreen
import br.com.exames.featurehistory.HelpScreen
import br.com.exames.featurehistory.HomeViewModel
import br.com.exames.featureimport.ImportScreen
import br.com.exames.featureimport.ImportViewModel
import br.com.exames.featureresults.ResultsScreen
import br.com.exames.featureresults.ResultsViewModel

object ResultRoutes {
    const val PATTERN = "results/{runId}"
    fun route(runId: String): String {
        require(ProcessingRunIds.isOpaque(runId)) { "RUN_ROUTE_INVALID" }
        return "results/$runId"
    }
}

private fun NavHostController.selectTab(route: String) = navigate(route) {
    popUpTo(ShellRoutes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun ExamesNavigation(sharing: br.com.exames.featureresults.ResultSharing) {
    val nav = rememberNavController()
    // Activity owner provides Hilt's factory. None of these models stores clinical state in SavedStateHandle.
    val importer: ImportViewModel = viewModel()
    val history: HistoryViewModel = viewModel()
    val home: HomeViewModel = viewModel()
    val results: ResultsViewModel = viewModel()
    val route by nav.currentBackStackEntryAsState()
    val current = route?.destination?.route
    val tabs = listOf(
        BottomDestination(ShellRoutes.HOME, "Início") { Icon(Icons.Filled.Home, contentDescription = null) },
        BottomDestination(ShellRoutes.EXAMS, "Exames") { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
        BottomDestination(ShellRoutes.HELP, "Ajuda") { Icon(Icons.Filled.Info, contentDescription = null) },
    )
    // Back from Exames/Ajuda returns to Início before leaving the app.
    BackHandler(enabled = current == ShellRoutes.EXAMS || current == ShellRoutes.HELP) { nav.selectTab(ShellRoutes.HOME) }
    Scaffold(bottomBar = {
        if (ShellRoutes.showsBottomBar(current)) ExamesBottomBar(tabs, current) { nav.selectTab(it) }
    }) { padding ->
        // Routes contain no patient data, raw text or document URIs.
        NavHost(navController = nav, startDestination = ShellRoutes.HOME, modifier = Modifier.padding(padding)) {
            composable(ShellRoutes.HOME) { HomeScreen(
                onSend = { nav.navigate(ShellRoutes.SEND) { launchSingleTop = true } },
                onOpenRun = { nav.navigate(ResultRoutes.route(it)) { launchSingleTop = true } },
                onExams = { nav.selectTab(ShellRoutes.EXAMS) }, model = home) }
            composable(ShellRoutes.SEND) { ImportScreen(
                onResults = { nav.navigate(ResultRoutes.route(it)) { launchSingleTop = true } },
                onHistory = { nav.selectTab(ShellRoutes.EXAMS) }, model = importer) }
            composable(ShellRoutes.EXAMS) { HistoryScreen(onBack = { nav.selectTab(ShellRoutes.HOME) },
                onRun = { nav.navigate(ResultRoutes.route(it)) { launchSingleTop = true } }, model = history) }
            composable(ShellRoutes.HELP) { HelpScreen(onExams = { nav.selectTab(ShellRoutes.EXAMS) }) }
            composable(ResultRoutes.PATTERN) { entry -> ResultsScreen(entry.arguments?.getString("runId").orEmpty(),
                onBack = { nav.popBackStack() }, model = results, sharing = sharing) }
        }
    }
}
