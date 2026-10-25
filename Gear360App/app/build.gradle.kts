import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Credenciales de firma de release ---------------------------------------
// Se buscan en dos lugares, por orden:
//   1. Variables de entorno (las inyecta GitHub Actions desde los secrets):
//        KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
//   2. Un archivo local keystore.properties (para compilar release en tu PC).
// Si no hay credenciales, el release se compila SIN firmar (no rompe el build).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName) ?: keystoreProps.getProperty(propName)

val ksFile = signingValue("KEYSTORE_FILE", "storeFile")
val ksPassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val ksAlias = signingValue("KEY_ALIAS", "keyAlias")
val ksKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val hasSigningConfig = ksFile != null && ksPassword != null &&
    ksAlias != null && ksKeyPassword != null

android {
    namespace = "com.gear360.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gear360.downloader"
        minSdk = 26          // Android 8.0: cubre casi todos los telefonos actuales
        targetSdk = 35       // Android 15
        // El numero de build lo pasa GitHub Actions por la variable de
        // entorno BUILD_NUMBER (github.run_number). En local, si no existe,
        // usamos 0. La version queda como "1.0.<build>".
        val buildNumber = (System.getenv("BUILD_NUMBER") ?: "0").toInt()
        versionCode = buildNumber + 1          // siempre creciente
        versionName = "1.0.$buildNumber"

        // ffmpeg-kit solo publica arm64-v8a y x86_64. Limitamos a esos ABIs
        // para que el build no busque librerias de 32 bits inexistentes.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                // Resolvemos la ruta del keystore de forma robusta:
                // si es absoluta la usamos tal cual; si es relativa, la
                // anclamos a la raiz del proyecto (donde vive el .jks),
                // no al modulo app/.
                val ksPath = File(ksFile!!)
                storeFile = if (ksPath.isAbsolute) ksPath
                            else File(rootProject.projectDir, ksFile)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Firma el release solo si hay credenciales disponibles.
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true   // para exponer VERSION_NAME a la app
    }
    // El plugin org.jetbrains.kotlin.plugin.compose gestiona la version del
    // compilador de Compose automaticamente (Kotlin 2.0+), ya no hace falta
    // declarar kotlinCompilerExtensionVersion.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Renombra el APK para incluir la version, p.ej. gear360-v1.0.11.apk,
    // en vez de siempre "app-release.apk". Asi cada descarga es distinguible.
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "gear360-v${variant.versionName}-${variant.buildType.name}.apk"
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

    // FFmpeg para el stitching 360 (dual-fisheye -> equirectangular) en el
    // telefono. Fork mantenido de FFmpegKit (la original fue retirada en 2025).
    // Variante LGPL "full" que incluye el filtro v360. Solo ABI arm64/x86_64.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
}
