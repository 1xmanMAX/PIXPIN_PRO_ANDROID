package com.forge.pixpin.data

import android.content.Context
import com.forge.pixpin.pin.PinState
import kotlinx.serialization.json.Json
import java.io.File

/** Persistencia de pines e historial en JSON (dataset pequeño: no hace falta Room). */
class PinRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pinsDir = File(context.filesDir, "pins")
    private val pinsFile = File(pinsDir, "pins.json")
    private val historyFile = File(pinsDir, "history.json")
    private val savedPinsFile = File(pinsDir, "saved.json")

    fun savePins(pins: List<PinState>) = write(pinsFile, pins)
    fun loadPins(): List<PinState> = read(pinsFile)

    fun saveHistory(items: List<PinState>) = write(historyFile, items)
    fun loadHistory(): List<PinState> = read(historyFile)

    /** Guarda la lista de pines marcados como guardados (isPinned = true). */
    fun saveSavedPins(pins: List<PinState>) = write(savedPinsFile, pins)

    /** Carga los pines guardados. */
    fun loadSavedPins(): List<PinState> = read(savedPinsFile)

    /** Guarda un solo pin añadiéndolo o actualizándolo en la lista de guardados. */
    fun saveSavedPin(pin: PinState) {
        val saved = loadSavedPins().toMutableList()
        val idx = saved.indexOfFirst { it.id == pin.id }
        if (idx >= 0) saved[idx] = pin else saved.add(pin)
        saveSavedPins(saved)
    }

    /** Elimina un pin de la lista de guardados por su id. */
    fun removeSavedPin(pinId: String) {
        val saved = loadSavedPins().filter { it.id != pinId }
        saveSavedPins(saved)
    }

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
