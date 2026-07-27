package com.forge.pixpin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pixpin_settings")

/** Ajustes de la app. Se amplía con nuevas claves a medida que crecen las fases. */
data class Settings(
    val defaultPinAlpha: Float = 1f,
    val historySize: Int = 10,
    val ballX: Int = -1,
    val ballY: Int = -1
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_PIN_ALPHA = floatPreferencesKey("default_pin_alpha")
        val HISTORY_SIZE = intPreferencesKey("history_size")
        val BALL_X = intPreferencesKey("ball_x")
        val BALL_Y = intPreferencesKey("ball_y")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            defaultPinAlpha = prefs[Keys.DEFAULT_PIN_ALPHA] ?: 1f,
            historySize = prefs[Keys.HISTORY_SIZE] ?: 10,
            ballX = prefs[Keys.BALL_X] ?: -1,
            ballY = prefs[Keys.BALL_Y] ?: -1
        )
    }

    suspend fun setDefaultPinAlpha(value: Float) {
        context.dataStore.edit { it[Keys.DEFAULT_PIN_ALPHA] = value.coerceIn(0.2f, 1f) }
    }

    suspend fun setHistorySize(value: Int) {
        context.dataStore.edit { it[Keys.HISTORY_SIZE] = value.coerceIn(0, 50) }
    }

    suspend fun setBallPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[Keys.BALL_X] = x
            it[Keys.BALL_Y] = y
        }
    }
}
