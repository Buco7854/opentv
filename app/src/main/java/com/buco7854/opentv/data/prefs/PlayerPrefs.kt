package com.buco7854.opentv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerDataStore by preferencesDataStore(name = "player_prefs")

data class SubtitleStyle(
    /** Multiplier on the default subtitle text size (0.5 .. 2.0). */
    val scale: Float = 1f,
    /** true = translucent background box, false = outlined text. */
    val background: Boolean = false,
    val bold: Boolean = false,
)

data class PlayerSettings(
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** ISO 639 code preferred for audio tracks; "" = let the stream decide. */
    val preferredAudioLang: String = "",
    /** ISO 639 code preferred for subtitle tracks; "" = no preference. */
    val preferredTextLang: String = "",
    val seekSeconds: Int = 10,
    /** androidx.media3.ui.AspectRatioFrameLayout resize mode constant. */
    val resizeMode: Int = 0, // RESIZE_MODE_FIT
    /** Fall back to software decoders when the hardware codec fails. */
    val decoderFallback: Boolean = true,
    val bufferPreset: Int = BUFFER_BALANCED,
    /** Simultaneous download transfers; DOWNLOADS_AUTO derives it from the provider's connection limit. */
    val downloadLimit: Int = DOWNLOADS_AUTO,
    /** SAF tree URI for downloads; "" = app-private storage. */
    val downloadDirUri: String = "",
    /** Browse movies/series as a poster grid instead of row lists. */
    val gridBrowse: Boolean = false,
) {
    companion object {
        const val BUFFER_FAST_START = 0
        const val BUFFER_BALANCED = 1
        const val BUFFER_STABLE = 2
        const val DOWNLOADS_AUTO = 0
    }
}

class PlayerPrefs(private val context: Context) {

    private object Keys {
        val SCALE = floatPreferencesKey("subtitle_scale")
        val BACKGROUND = booleanPreferencesKey("subtitle_background")
        val BOLD = booleanPreferencesKey("subtitle_bold")
        val AUDIO_LANG = stringPreferencesKey("preferred_audio_lang")
        val TEXT_LANG = stringPreferencesKey("preferred_text_lang")
        val SEEK_SECONDS = intPreferencesKey("seek_seconds")
        val RESIZE_MODE = intPreferencesKey("resize_mode")
        val DECODER_FALLBACK = booleanPreferencesKey("decoder_fallback")
        val BUFFER_PRESET = intPreferencesKey("buffer_preset")
        val DOWNLOAD_LIMIT = intPreferencesKey("download_limit")
        val DOWNLOAD_DIR = stringPreferencesKey("download_dir_uri")
        val GRID_BROWSE = booleanPreferencesKey("grid_browse")
    }

    val settings: Flow<PlayerSettings> = context.playerDataStore.data.map { prefs ->
        PlayerSettings(
            subtitleStyle = SubtitleStyle(
                scale = prefs[Keys.SCALE] ?: 1f,
                background = prefs[Keys.BACKGROUND] ?: false,
                bold = prefs[Keys.BOLD] ?: false,
            ),
            preferredAudioLang = prefs[Keys.AUDIO_LANG] ?: "",
            preferredTextLang = prefs[Keys.TEXT_LANG] ?: "",
            seekSeconds = prefs[Keys.SEEK_SECONDS] ?: 10,
            resizeMode = prefs[Keys.RESIZE_MODE] ?: 0,
            decoderFallback = prefs[Keys.DECODER_FALLBACK] ?: true,
            bufferPreset = prefs[Keys.BUFFER_PRESET] ?: PlayerSettings.BUFFER_BALANCED,
            downloadLimit = prefs[Keys.DOWNLOAD_LIMIT] ?: PlayerSettings.DOWNLOADS_AUTO,
            downloadDirUri = prefs[Keys.DOWNLOAD_DIR] ?: "",
            gridBrowse = prefs[Keys.GRID_BROWSE] ?: false,
        )
    }

    suspend fun save(settings: PlayerSettings) {
        context.playerDataStore.edit { prefs ->
            prefs[Keys.SCALE] = settings.subtitleStyle.scale
            prefs[Keys.BACKGROUND] = settings.subtitleStyle.background
            prefs[Keys.BOLD] = settings.subtitleStyle.bold
            prefs[Keys.AUDIO_LANG] = settings.preferredAudioLang
            prefs[Keys.TEXT_LANG] = settings.preferredTextLang
            prefs[Keys.SEEK_SECONDS] = settings.seekSeconds
            prefs[Keys.RESIZE_MODE] = settings.resizeMode
            prefs[Keys.DECODER_FALLBACK] = settings.decoderFallback
            prefs[Keys.BUFFER_PRESET] = settings.bufferPreset
            prefs[Keys.DOWNLOAD_LIMIT] = settings.downloadLimit
            prefs[Keys.DOWNLOAD_DIR] = settings.downloadDirUri
            prefs[Keys.GRID_BROWSE] = settings.gridBrowse
        }
    }
}
