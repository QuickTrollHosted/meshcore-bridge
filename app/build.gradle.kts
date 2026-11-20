plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versionnage automatique : versionCode = nombre de commits ; versionName = tag `vX.Y.Z` en release,
// sinon `<dernier tag>-dev.<nb commits>`. Le CI doit cloner avec fetch-depth: 0.
fun git(vararg args: String): String? = try {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }
        .standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) { null }

val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val refTag = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }
val lastTag = git("describe", "--tags", "--abbrev=0", "--match", "v*")
val computedVersionName = refTag?.removePrefix("v")
    ?: "${lastTag?.removePrefix("v") ?: "0.0.0"}-dev.$commitCount"

android {
    namespace = "fr.qth.meshcorebridge"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "fr.qth.meshcorebridge"
        minSdk = 26
        // targetSdk 36 : l'API 37 impose ACCESS_LOCAL_NETWORK pour le trafic LAN (phase 2).
        // Le loopback (127.0.0.1) n'est pas concerné ; on reste en 36 tant que le LAN n'est pas géré.
        targetSdk = 36
        versionCode = commitCount
        versionName = computedVersionName
    }

    // Signature de release : uniquement si les variables d'environnement sont fournies (CI ou local).
    // Aucune valeur secrète dans le dépôt.
    val keystorePath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
