import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.google.services)
  id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

val whisperSource = file("src/main/cpp/whisper.cpp/CMakeLists.txt")

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.readmark.kxpv"
    minSdk = 24
    targetSdk = 36
    // GitHub builds set these (see .github/workflows/release.yml); local builds stay 1 / "1.0".
    versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
    versionName = System.getenv("APP_VERSION_NAME") ?: "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // Offline transcription (whisper.cpp) is built for 64-bit ARM, which the Boox tablets use.
    if (whisperSource.exists()) ndk { abiFilters += listOf("arm64-v8a") }
  }

  // whisper.cpp is fetched by the GitHub build; without it the app builds without offline transcription.
  if (whisperSource.exists()) {
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Signed with the release key when it is available (GitHub builds); otherwise left unsigned.
      val releaseKey = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
      signingConfig = if (releaseKey.exists()) signingConfigs.getByName("release") else null
    }
    debug {
      // Local builds: AI Studio's debug.keystore is not in the export, so fall back to the standard
      // Android debug key, and install side by side with the AI Studio build (".local" suffix) so the
      // existing app and its library/highlights are never replaced or wiped.
      if (file("${rootDir}/debug.keystore").exists()) {
        signingConfig = signingConfigs.getByName("debugConfig")
      } else {
        applicationIdSuffix = ".local"
        versionNameSuffix = "-local"
      }
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.pdfbox.android)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.kotlinx.coroutines.play.services)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.readium.shared)
  implementation(libs.jsoup)
  implementation(libs.work.runtime.ktx)
  implementation(libs.mlkit.digital.ink)
  implementation(libs.readium.streamer)
  implementation(libs.readium.navigator)
  implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
