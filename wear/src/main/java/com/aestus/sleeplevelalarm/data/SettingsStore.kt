package com.aestus.sleeplevelalarm.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.aestus.sleep.core.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferences by preferencesDataStore("alarm_settings")

class SettingsStore(private val context: Context) {
    private val mode = stringPreferencesKey("mode")
    private val hour = intPreferencesKey("hour")
    private val minute = intPreferencesKey("minute")
    private val cycles = intPreferencesKey("cycles")
    private val sound = booleanPreferencesKey("sound")
    val settings = context.preferences.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { p ->
        AlarmSettings(AlarmMode.entries.find { it.name == p[mode] } ?: AlarmMode.DEADLINE,
            (p[hour] ?: 7).coerceIn(0, 23), (p[minute] ?: 0).coerceIn(0, 59),
            (p[cycles] ?: 5).coerceIn(1, 6), p[sound] ?: true)
    }
    suspend fun save(value: AlarmSettings) {
        context.preferences.edit { p ->
            p[mode] = value.mode.name; p[hour] = value.hour; p[minute] = value.minute
            p[cycles] = value.cycles; p[sound] = value.sound
        }
    }
}
