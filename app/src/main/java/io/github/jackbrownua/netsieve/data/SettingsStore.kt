package io.github.jackbrownua.netsieve.data

import android.content.Context
import android.content.SharedPreferences

enum class SplitMode {
    EXCLUDE_SELECTED,
    INCLUDE_SELECTED
}

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("netsieve_prefs", Context.MODE_PRIVATE)

    var selectedPresetId: String
        get() = prefs.getString("selected_preset_id", "balanced") ?: "balanced"
        set(value) = prefs.edit().putString("selected_preset_id", value).apply()

    var customArgs: String
        get() = prefs.getString("custom_args", "-d 1 -o 1+s -s 2+s -a 1") ?: "-d 1 -o 1+s -s 2+s -a 1"
        set(value) = prefs.edit().putString("custom_args", value).apply()

    var dnsPrimary: String
        get() = prefs.getString("dns_primary", "1.1.1.1") ?: "1.1.1.1"
        set(value) = prefs.edit().putString("dns_primary", value).apply()

    var dnsSecondary: String
        get() = prefs.getString("dns_secondary", "8.8.8.8") ?: "8.8.8.8"
        set(value) = prefs.edit().putString("dns_secondary", value).apply()

    var splitMode: SplitMode
        get() {
            val raw = prefs.getString("split_mode", SplitMode.EXCLUDE_SELECTED.name)
            return runCatching { SplitMode.valueOf(raw ?: "") }.getOrDefault(SplitMode.EXCLUDE_SELECTED)
        }
        set(value) = prefs.edit().putString("split_mode", value.name).apply()

    var selectedApps: Set<String>
        get() = prefs.getStringSet("selected_apps", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("selected_apps", value.toSet()).apply()

    var darkThemeOverride: Boolean?
        get() = if (prefs.contains("dark_theme")) prefs.getBoolean("dark_theme", true) else null
        set(value) {
            if (value == null) {
                prefs.edit().remove("dark_theme").apply()
            } else {
                prefs.edit().putBoolean("dark_theme", value).apply()
            }
        }
}
