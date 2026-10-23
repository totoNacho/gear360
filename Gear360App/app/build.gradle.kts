plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gear360.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gear360.downloader"
        minSdk = 26          // Android 8.0: cubre casi todos los telefonos actuales
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // El plugin org.jetbrains.kotlin.plugin.compose gestiona la version del
    // compilador de Compose automaticamente (Kotlin 2.0+), ya no hace falta
    // declarar kotlinCompilerExtensionVersion.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Red: OkHttp para hablar con la API OSC de la camara
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON
    implementation("org.json:json:20240303")
    // Carga de imagenes/miniaturas
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Corrutinas para trabajo en segundo plano
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
