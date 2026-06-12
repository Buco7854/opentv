package com.buco7854.opentv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerDataStore by preferencesDataStore(name = "player_prefs")

class SubtitleStyle(
    /** Multiplier on the default subtitle text size (0.5 .. 2.0). */
    val scale: Float = 1f,
    /** true = translucent background box, false = outlined text. */
    val background: Boolean = false,
    val bold: Boolean = false,
)

class PlayerPrefs(private val context: Context) {

    private object Keys {
        val SCALE = floatPreferencesKey("subtitle_scale")
        val BACKGROUND = booleanPreferencesKey("subtitle_background")
        val BOLD = booleanPreferencesKey("subtitle_bold")
    }

    val subtitleStyle: Flow<SubtitleStyle> = context.playerDataStore.data.map { prefs ->
        SubtitleStyle(
            scale = prefs[Keys.SCALE] ?: 1f,
            background = prefs[Keys.BACKGROUND] ?: false,
            bold = prefs[Keys.BOLD] ?: false,
        )
    }

    suspend fun save(style: SubtitleStyle) {
        context.playerDataStore.edit { prefs ->
            prefs[Keys.SCALE] = style.scale
            prefs[Keys.BACKGROUND] = style.background
            prefs[Keys.BOLD] = style.bold
        }
    }
}
