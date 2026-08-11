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
        // **Esto sube en cada APK que salga de aquí.** Se había quedado en 0.4.0
        // mientras se repartían archivos llamados v0.5.0, v0.5.1 y v0.5.2: los
        // tres se declaraban la misma versión y con el mismo `versionCode`, así
        // que Android no tenía forma de saber que uno era más nuevo que otro
        // —a veces se niega a instalar encima— y desde el móvil no había manera
        // de comprobar cuál estaba puesto.
        versionCode = 44
        versionName = "0.12.2"
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

    // Nota: `enableV1Signing` aquí NO surte efecto. Con minSdk 29 el plugin
    // decide que la firma v1 sobra y la omite igualmente. Los APK que se
    // reparten para instalar a mano se vuelven a firmar con apksigner
    // pasándole `--min-sdk-version 21`, que es lo que la incluye de verdad.

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

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Para probar el andamiaje de las ventanas overlay (vistas reales, sin dispositivo)
    testImplementation("org.robolectric:robolectric:4.15.1")
}
