plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.forge.pixpin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.forge.pixpin"
        minSdk = 29
        targetSdk = 36
        // Vosk trae su biblioteca nativa: solo las dos arquitecturas de los teléfonos.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // **Esto sube en cada APK que salga de aquí.** Se había quedado en 0.4.0
        // mientras se repartían archivos llamados v0.5.0, v0.5.1 y v0.5.2: los
        // tres se declaraban la misma versión y con el mismo `versionCode`, así
        // que Android no tenía forma de saber que uno era más nuevo que otro
        // —a veces se niega a instalar encima— y desde el móvil no había manera
        // de comprobar cuál estaba puesto.
        versionCode = 81
        versionName = "0.28.3"
    }

    buildTypes {
        release {
            // **Los 73 MB eran esto.** Sin bloque de release solo existía el
            // build de depuración: sin minificar y arrastrando el paquete
            // entero de iconos de Material, del que se usan treinta. Con R8 se
            // queda en una fracción, y eso se nota cada vez que hay que
            // bajárselo por un enlace y meterlo a mano en el móvil.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Firmado con la clave de depuración a propósito: esto no va a
            // Google Play, se reparte por un enlace y se instala a mano. Sin
            // firma el APK no se puede instalar, y montar una clave de release
            // para un proyecto personal es ceremonia sin nadie que la lea.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // **El APK que sale de aquí NO está listo para repartir.** Lleva solo firma v2, y el
    // instalador que abre un archivo descargado a mano quiere además la v1 (JAR): sin ella el
    // teléfono dice «hay un problema con el archivo de la app» y no explica más. Pasó el
    // 9-sep-2026 y costó un rato, porque el APK estaba bien — le faltaba una firma que Gradle
    // decide omitir. Ponerlo aquí no sirve: con minSdk 29 `enableV1Signing` no surte efecto.
    // Antes de subirlo hay que pasarlo por `herramientas/apk-para-repartir.sh`, que lo vuelve
    // a firmar con apksigner y `--min-sdk-version 21`, que es lo único que la incluye.

    buildFeatures {
        compose = true
        // Para poder enseñar la versión dentro de la app. Si el número solo vive
        // en este archivo, desde el móvil no hay forma de saber qué build tienes.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Nota: no se puede activar unitTests.isIncludeAndroidResources porque el
    // directorio de build vive en otra unidad (C:) que el proyecto (H:) y AGP
    // no sabe calcular la ruta relativa. Los tests de Robolectric de aquí no
    // necesitan recursos.

    lint {
        // Ruido que no depende del código: local.properties es de esta máquina y
        // las versiones están fijadas a propósito (solo existe el SDK 36).
        disable += setOf(
            "PropertyEscape", "OldTargetApi", "GradleDependency", "NewerVersionAvailable"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    // Reconocimiento de voz en el aparato, de archivo y con tiempos por palabra. Ver MotorVosk.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // **La cámara de atrás, para ver el croquis puesto en el sitio.** Ver [Croquis3DCamara].
    //
    // CameraX y no `camera2` a pelo: lo que hay que acertar aquí es justo lo que `camera2`
    // deja en manos de quien llama —la orientación del sensor contra la de la pantalla, el
    // recorte del formato, abrir y cerrar al ritmo del ciclo de vida— y equivocarse en eso
    // es una vista negra o torcida en el teléfono de alguien. Son un par de megas y evitan
    // el problema entero.
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Para probar el andamiaje de las ventanas overlay (vistas reales, sin dispositivo)
    testImplementation("org.robolectric:robolectric:4.15.1")
}
