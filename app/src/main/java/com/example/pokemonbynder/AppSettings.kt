package com.example.pokemonbynder

import android.content.Context
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.scanner.ScanSource
import com.example.pokemonbynder.ui.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Aspetto dell'app: segue il telefono oppure è sempre chiaro o sempre scuro. */
enum class ThemeMode(val label: String) {
    SYSTEM("Come il telefono"),
    LIGHT("Chiaro"),
    DARK("Scuro");

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Le impostazioni dell'app, con i valori di partenza. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Fonte proposta prima della scansione. */
    val scanSource: ScanSource = ScanSource.CATALOG,
    /** Dopo ogni carta aggiunta la fotocamera si riapre da sola. */
    val continuousScan: Boolean = false,
    /** Carte del binder come lista compatta invece che a griglia. */
    val listView: Boolean = false,
    val sortOrder: SortOrder = SortOrder.RECENT,
    /** Condizione proposta quando si aggiunge una carta. */
    val defaultCondition: CardCondition = CardCondition.NEAR_MINT
)

/** Salva le impostazioni sul telefono (le stesse chiavi usate dalle versioni precedenti restano valide). */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        themeMode = ThemeMode.fromName(prefs.getString(KEY_THEME, null)),
        scanSource = ScanSource.fromName(prefs.getString(KEY_SOURCE, null)),
        continuousScan = prefs.getBoolean(KEY_CONTINUOUS, false),
        listView = prefs.getBoolean(KEY_LIST_VIEW, false),
        sortOrder = SortOrder.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortOrder.RECENT,
        defaultCondition = CardCondition.entries.firstOrNull { it.name == prefs.getString(KEY_CONDITION, null) }
            ?: CardCondition.NEAR_MINT
    )

    /** Applica una modifica e la salva subito. */
    fun update(change: (AppSettings) -> AppSettings) {
        val updated = change(_state.value)
        _state.value = updated
        prefs.edit()
            .putString(KEY_THEME, updated.themeMode.name)
            .putString(KEY_SOURCE, updated.scanSource.name)
            .putBoolean(KEY_CONTINUOUS, updated.continuousScan)
            .putBoolean(KEY_LIST_VIEW, updated.listView)
            .putString(KEY_SORT, updated.sortOrder.name)
            .putString(KEY_CONDITION, updated.defaultCondition.name)
            .apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_SOURCE = "scan_source"
        const val KEY_CONTINUOUS = "continuous_scan"
        const val KEY_LIST_VIEW = "list_view"
        const val KEY_SORT = "sort_order"
        const val KEY_CONDITION = "default_condition"
    }
}
