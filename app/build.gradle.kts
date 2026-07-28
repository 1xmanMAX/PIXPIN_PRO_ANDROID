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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
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
