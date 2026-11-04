package br.com.exames.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.exames.domain.validation.ProcessingRunIds
import br.com.exames.featureimport.ImportScreen
import br.com.exames.featureimport.ImportViewModel
import br.com.exames.featureresults.ResultsScreen
import br.com.exames.featureresults.ResultsViewModel
import br.com.exames.featurehistory.HistoryScreen
import br.com.exames.featurehistory.HistoryViewModel

object ResultRoutes {
    const val PATTERN = "results/{runId}"
    fun route(runId: String): String {
        require(ProcessingRunIds.isOpaque(runId)) { "RUN_ROUTE_INVALID" }
        return "results/$runId"
    }
}

@Composable
fun ExamesNavigation() {
    val nav = rememberNavController()
    // Activity owner provides Hilt's factory. None of these models stores clinical state in SavedStateHandle.
    val importer: ImportViewModel = viewModel()
    val history: HistoryViewModel = viewModel()
    val results: ResultsViewModel = viewModel()
    // Routes contain no patient data, raw text or document URIs.
    NavHost(navController = nav, startDestination = "import") {
        composable("import") { ImportScreen(
            onResults = { nav.navigate(ResultRoutes.route(it)) { launchSingleTop = true } },
            onHistory = { nav.navigate("history") { launchSingleTop = true } }, model = importer) }
        composable(ResultRoutes.PATTERN) { entry -> ResultsScreen(entry.arguments?.getString("runId").orEmpty(),
            onBack = { nav.popBackStack() }, model = results) }
        composable("history") { HistoryScreen(onBack = { nav.popBackStack() },
            onRun = { nav.navigate(ResultRoutes.route(it)) { launchSingleTop = true } }, model = history) }
    }
}
