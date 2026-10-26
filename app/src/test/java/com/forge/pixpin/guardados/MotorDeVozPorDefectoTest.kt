package com.forge.pixpin.guardados

import com.forge.pixpin.data.MOTOR_GOOGLE
import com.forge.pixpin.data.MOTOR_POR_DEFECTO
import com.forge.pixpin.data.MOTOR_QWEN3
import com.forge.pixpin.data.MOTOR_VOSK
import com.forge.pixpin.data.MOTOR_WHISPER
import com.forge.pixpin.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **El motor de voz de fábrica es el del PC** (26-oct-2026): Whisper (`MotorDeVoz::Whisper`,
 * `#[default]` en `pixpin-store`). Lo elegido a mano no se toca, y Whisper que no carga pasa con Vosk.
 */
class MotorDeVozPorDefectoTest {

    @Test fun deFabricaWhisperComoElPc() {
        assertEquals(MOTOR_WHISPER, MOTOR_POR_DEFECTO)
        assertEquals(MOTOR_WHISPER, Settings().motorDeVoz)
    }

    @Test fun loElegidoSeRespetaYWhisperQueNoCargaPasaConVosk() {
        for (m in listOf(MOTOR_VOSK, MOTOR_WHISPER, MOTOR_GOOGLE, MOTOR_QWEN3)) assertEquals(m, Transcriptor.motorQueVa(m, whisperVa = true))
        assertEquals(MOTOR_VOSK, Transcriptor.motorQueVa(MOTOR_WHISPER, whisperVa = false))
        assertEquals(MOTOR_VOSK, Transcriptor.motorQueVa(MOTOR_QWEN3, whisperVa = false))
        assertEquals(MOTOR_GOOGLE, Transcriptor.motorQueVa(MOTOR_GOOGLE, whisperVa = false))
        assertEquals(MOTOR_VOSK, Transcriptor.motorQueVa(MOTOR_VOSK, whisperVa = false))
    }
}
