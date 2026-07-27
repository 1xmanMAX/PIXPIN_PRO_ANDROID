package com.forge.pixpin.data

import android.content.Context
import com.forge.pixpin.pin.PinState
import kotlinx.serialization.json.Json
import java.io.File

/** Persistencia de pines e historial en JSON (dataset pequeño: no hace falta Room). */
class PinRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pinsFile = File(File(context.filesDir, "pins"), "pins.json")
    private val historyFile = File(File(context.filesDir, "pins"), "history.json")

    fun savePins(pins: List<PinState>) = write(pinsFile, pins)
    fun loadPins(): List<PinState> = read(pinsFile)

    fun saveHistory(items: List<PinState>) = write(historyFile, items)
    fun loadHistory(): List<PinState> = read(historyFile)

    private fun write(file: File, items: List<PinState>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items))
        }
    }

    private fun read(file: File): List<PinState> {
        return runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<PinState>>(file.readText())
        }.getOrDefault(emptyList())
    }
}
