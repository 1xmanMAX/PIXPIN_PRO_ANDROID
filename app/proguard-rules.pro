# Reglas de R8 para el build de release.
#
# Lo que hay aquí es lo que R8 no puede deducir solo: los sitios donde algo se
# usa por reflexión y por tanto "parece" muerto desde fuera.

# kotlinx.serialization genera un serializador por clase y lo busca por nombre.
# Sin esto, R8 borra los serializadores de las clases que solo se instancian al
# leer JSON --los pines guardados, las escenas, los proyectos-- y la app arranca
# bien y revienta al restaurar, que es el peor momento posible.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.forge.pixpin.** {
    *** Companion;
}
-keepclasseswithmembers class com.forge.pixpin.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.forge.pixpin.**$$serializer { *; }

# Los modelos que van y vienen del disco, enteros: sus nombres de campo SON el
# formato del archivo. Renombrarlos deja los datos de ayer ilegibles.
-keep @kotlinx.serialization.Serializable class com.forge.pixpin.** { *; }

# Los servicios, receptores y actividades se instancian por nombre desde el
# manifiesto; R8 los conserva por el manifiesto, pero los tiles de ajustes
# rápidos y el receptor de arranque se han olvidado en más de un proyecto.
-keep class com.forge.pixpin.floating.CaptureTileService { *; }
-keep class com.forge.pixpin.pin.BootReceiver { *; }
