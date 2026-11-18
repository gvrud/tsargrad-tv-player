package ru.tsargrad.tv

import android.content.Context
import androidx.core.content.edit

class PlayerPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    var source: StreamSource
        get() = runCatching {
            StreamSource.valueOf(
                prefs.getString(KEY_SOURCE, StreamSource.RUTUBE.name) ?: StreamSource.RUTUBE.name
            )
        }.getOrDefault(StreamSource.RUTUBE)
        set(value) {
            prefs.edit { putString(KEY_SOURCE, value.name) }
        }

    var audioOnly: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ONLY, false)
        set(value) {
            prefs.edit { putBoolean(KEY_AUDIO_ONLY, value) }
        }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_AUDIO_ONLY = "audio_only"
    }
}
