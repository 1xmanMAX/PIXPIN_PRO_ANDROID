package com.forge.pixpin.pin

import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Andamiaje de las ventanas overlay. Regresión concreta que cubre:
 * Compose busca los owners del ciclo de vida subiendo hasta la vista RAÍZ de la
 * ventana; si solo los tiene el ComposeView hijo, la app se cierra con
 * «ViewTreeLifecycleOwner not found» en cuanto se muestra la bola o un pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayComposeWindowTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `la vista raiz expone los tres owners`() {
        val window = OverlayComposeWindow(context) {}
        val root = window.view

        assertNotNull("falta LifecycleOwner en la raíz", root.findViewTreeLifecycleOwner())
        assertNotNull("falta ViewModelStoreOwner en la raíz", root.findViewTreeViewModelStoreOwner())
        assertNotNull(
            "falta SavedStateRegistryOwner en la raíz",
            root.findViewTreeSavedStateRegistryOwner()
        )
    }

    @Test
    fun `el ciclo de vida llega a RESUMED al adjuntar`() {
        val window = OverlayComposeWindow(context) {}
        window.onAttached()
        val lifecycle = window.view.findViewTreeLifecycleOwner()!!.lifecycle
        assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState)

        window.onDetached()
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.currentState)
    }

    @Test
    fun `sin handler la ventana no intercepta los toques`() {
        val window = OverlayComposeWindow(context) {}
        // Sin reconocedor instalado el contenido Compose debe recibir los eventos
        // (así siguen funcionando los botones de la barra de acciones y el menú).
        assertNotNull(window.view)
        window.setTouchHandler(null)
        // No debe lanzar aunque no haya nadie escuchando.
        window.view.dispatchTouchEvent(downEvent())
    }

    @Test
    fun `con handler todos los eventos van al reconocedor`() {
        val window = OverlayComposeWindow(context) {}
        var received = 0
        window.setTouchHandler(View.OnTouchListener { _, _ -> received++; true })

        assertTrue(window.view.dispatchTouchEvent(downEvent()))
        assertTrue(window.view.dispatchTouchEvent(downEvent()))
        assertEquals(2, received)
    }

    @Test
    fun `ocultar conserva la ventana`() {
        val window = OverlayComposeWindow(context) {}
        window.isContentVisible = false
        assertEquals(View.GONE, window.view.visibility)
        window.isContentVisible = true
        assertEquals(View.VISIBLE, window.view.visibility)
    }

    private fun downEvent(): MotionEvent =
        MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
}
