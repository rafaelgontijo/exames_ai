package br.com.exames.datalocal

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.generalPreferences by preferencesDataStore("general_preferences")
/** Only non-clinical UI settings belong here. No patient, report, URI or clinical values. */
class GeneralPreferences(context: Context) {
    private val store = context.applicationContext.generalPreferences
    val useDarkTheme = store.data.map { it[DARK_THEME] ?: false }
    suspend fun setDarkTheme(enabled: Boolean) { store.edit { it[DARK_THEME] = enabled } }
    companion object { private val DARK_THEME = booleanPreferencesKey("dark_theme") }
}
