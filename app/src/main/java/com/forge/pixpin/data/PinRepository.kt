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

    /**
     * Lee una lista de pines, **descartando los del croquis retirado**.
     *
     * El croquis era el editor de tipo CAD, y se ha ido: ya no hay con qué
     * dibujarlo ni con qué pintarlo, así que un pin de ese tipo solo podría
     * salir como un recuadro vacío que no se puede ni editar ni copiar.
     *
     * **El valor sigue en `PinType` a propósito.** Quitarlo del enum haría que
     * `decodeFromString` fallara al encontrarse un `"CROQUIS"` guardado, y como
     * aquí un fallo devuelve la lista vacía, quien tuviera un solo croquis
     * perdería **todos** sus pines de golpe.
     *
     * Los archivos de la carpeta `pins/croquis` **no se borran**: dejan de
     * usarse, pero siguen en el almacenamiento de la aplicación por si
     * hicieran falta.
     */
    private fun read(file: File): List<PinState> {
        return runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<PinState>>(file.readText())
                .filter { it.type != com.forge.pixpin.pin.PinType.CROQUIS }
        }.getOrDefault(emptyList())
    }
}
