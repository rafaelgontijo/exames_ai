package br.com.exames.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guard: no production module may persist UI state through SavedStateHandle, Bundle or rememberSaveable. */
class NoRestorableClinicalStateTest {
    @Test fun productionSourcesDoNotUseRestorableStateApis() {
        val forbidden = listOf("SavedStateHandle", "rememberSaveable", "onSaveInstanceState", "putString(", "mapSaver", "listSaver")
        val directories = listOf("src/extraction", "src/workbench", "../feature-import/src/main",
            "../feature-results/src/main", "../feature-history/src/main", "../core-ui/src/main")
        val hits = directories.flatMap { directory ->
            File(directory).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                val t = f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//.*"), ""); forbidden.filter { it in t }.map { "${f.name}:$it" }
            }.toList()
        }
        assertTrue("RESTORABLE_STATE_API_USED $hits", hits.isEmpty())
    }
}
