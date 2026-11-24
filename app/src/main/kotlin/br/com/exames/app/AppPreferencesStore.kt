package br.com.exames.app

import android.content.Context

/**
 * Private non-PII application preferences (cloud fallback opt-in consent and Wi-Fi download restriction).
 * Never stores clinical content, exam data, URIs, or personal identifiers.
 */
internal interface HybridPreferences {
    var cloudFallbackOptIn: Boolean
    var downloadOnWifiOnly: Boolean
}

internal class AppPreferencesStore(context: Context) : HybridPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var cloudFallbackOptIn: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_OPT_IN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLOUD_OPT_IN, value).apply()
        }

    override var downloadOnWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "exames_non_pii_prefs"
        private const val KEY_CLOUD_OPT_IN = "cloud_fallback_opt_in"
        private const val KEY_WIFI_ONLY = "download_on_wifi_only"
    }
}
