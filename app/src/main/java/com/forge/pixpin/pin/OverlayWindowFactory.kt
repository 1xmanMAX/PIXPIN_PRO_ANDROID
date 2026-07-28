package com.forge.pixpin.pin

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * Aloja una vista Compose dentro de una ventana overlay (WindowManager).
 * Las ventanas overlay no tienen una Activity detrás, así que hay que darle
 * a ComposeView sus propios Lifecycle/ViewModel/SavedState owners y el tema de
 * la app (si no, Material usaría su paleta por defecto).
 */
class OverlayComposeWindow(context: Context, content: @Composable () -> Unit) {

    private val owner = OverlayLifecycleOwner()

    private val composeView = ComposeView(context).apply {
        setContent { PixPinTheme { content() } }
    }

    /**
     * Los owners van en la RAÍZ de la ventana, no en el ComposeView: Compose los
     * busca subiendo hasta la vista raíz para crear el recompositor, y si solo
     * los tiene el hijo lanza «ViewTreeLifecycleOwner not found» al adjuntarse.
     */
    private val host = GestureHostLayout(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** La vista que se añade al WindowManager. */
    val view: View get() = host

    /**
     * Instala el reconocedor de gestos de la ventana. Se hace en el contenedor
     * y no en el ComposeView porque un OnTouchListener sobre un ViewGroup no
     * llega a ejecutarse si un hijo consume el evento antes.
     */
    fun setTouchHandler(handler: View.OnTouchListener?) {
        host.touchHandler = handler
    }

    /**
     * Oculta la ventana sin destruirla: conserva posición, tamaño y estado.
     * Se usa para "ocultar todos los pines" y para que los overlays no salgan
     * dentro de la propia captura de pantalla.
     */
    var isContentVisible: Boolean
        get() = host.visibility == View.VISIBLE
        set(value) {
            host.visibility = if (value) View.VISIBLE else View.GONE
        }

    fun onAttached() = owner.resume()
    fun onDetached() = owner.destroy()
}

/**
 * Contenedor que puede quedarse con todos los toques antes de que lleguen al
 * contenido Compose. Sin handler se comporta como un FrameLayout normal (así la
 * barra de acciones y el menú siguen siendo botones Compose corrientes).
 */
private class GestureHostLayout(context: Context) : FrameLayout(context) {

    var touchHandler: View.OnTouchListener? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handler = touchHandler ?: return super.dispatchTouchEvent(event)
        handler.onTouch(this, event)
        return true
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var restored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun resume() {
        if (!restored) {
            savedStateController.performRestore(null)
            restored = true
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
