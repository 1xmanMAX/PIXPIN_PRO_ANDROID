package com.forge.pixpin.guardados

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.sincro.EntrePersonas
import com.forge.pixpin.sincro.LadosDelChat
import com.forge.pixpin.sincro.Quien
import java.io.File

/**
 * **De quién es cada captura de una clase** (28-oct-2026, §14 de la guía del PC: «en la carpeta de la
 * grabación y en "Estudiar la clase" cada foto de otra persona lleva su círculo con la inicial en su
 * color —el mismo de sus burbujas en el chat compartido— y, si toca, la pastilla "hora sin
 * comprobar"»). Lo mío no lleva nada: es lo de siempre.
 */
data class AutorDeCaptura(
    /** El nombre de la persona (null: mía, solo con la hora sin comprobar). */
    val nombre: String?,
    /** Su color (ARGB), el de sus burbujas. */
    val color: Long,
    /** La foto llegó por su hora sin desfase medido de su aparato (`horaSinComprobar`). */
    val sinComprobar: Boolean
) {
    val inicial: String get() = nombre?.trim()?.firstOrNull()?.uppercase() ?: "?"
}

/**
 * **Los autores de las capturas de [m]**, en el mismo orden que [capturasDeLaLetra] (las que siguen en
 * el chat y en el disco): null donde no hay nada que enseñar. [lados] dice quién es quién en un chat
 * compartido (null en uno normal: todo es mío). Sin Android: lo prueba `AutoresDeLaClaseTest`.
 */
fun autoresDeLaLetra(m: Mensaje, todos: List<Mensaje>, lados: LadosDelChat?, existe: (String) -> Boolean = { File(it).exists() }): List<AutorDeCaptura?> {
    val porId = todos.associateBy { it.id }
    val autores = ArrayList<AutorDeCaptura?>()
    for (e in GrabarClase.capturasDe(m)) {
        val foto = porId[e.id] ?: continue
        if (foto.ruta?.let(existe) != true) continue
        val aparato = PorSuHora.autorEnLaNota(m, e.id) ?: foto.aparato
        val quien = lados?.quien(aparato) as? Quien.Otro
        val sin = PorSuHora.sinComprobar(m, e.id)
        autores.add(
            if (quien == null && !sin) null
            else AutorDeCaptura(quien?.nombre, quien?.let { EntrePersonas.colorDe(it.nombre) } ?: 0xFF888888, sin)
        )
    }
    return autores
}

/**
 * **El círculo del autor** (su inicial en su color) arriba a la izquierda de la miniatura y, si su
 * hora no se comprobó, un reloj pequeño debajo: discreto, como la chapa del PC.
 */
@Composable
fun ChapaDeAutor(a: AutorDeCaptura, modifier: Modifier = Modifier) {
    Column(modifier.padding(3.dp), horizontalAlignment = Alignment.Start) {
        if (a.nombre != null) Box(
            Modifier.size(18.dp).clip(CircleShape).background(Color(a.color)).border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(a.inicial, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        if (a.sinComprobar) Text(
            "🕒", fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 3.dp)
        )
    }
}
