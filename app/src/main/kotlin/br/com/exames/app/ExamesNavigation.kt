package br.com.exames.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.exames.featureimport.ImportScreen
import br.com.exames.featureresults.ResultsScreen
import br.com.exames.featurehistory.HistoryScreen

@Composable
fun ExamesNavigation() {
    val nav = rememberNavController()
    // Routes contain no patient data, raw text or document URIs.
    NavHost(navController = nav, startDestination = "import") {
        composable("import") { ImportScreen(
            onResults = { nav.navigate("results") }, onHistory = { nav.navigate("history") }) }
        composable("results") { ResultsScreen(onBack = { nav.popBackStack() }) }
        composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
    }
}
