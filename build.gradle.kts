plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}

// El proyecto vive en una carpeta sincronizada (Google Drive): los build outputs
// van fuera para evitar bloqueos y ruido de sincronización.
allprojects {
    val externalRoot = File(System.getenv("LOCALAPPDATA") ?: rootDir.absolutePath, "pixpin-build")
    layout.buildDirectory.set(File(externalRoot, project.name))
}
