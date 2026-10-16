import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

val localProperties =
    Properties().apply {
      val file = rootProject.file("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

android {
  namespace = "com.joegineered.ontheroad"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.joegineered.ontheroad"
    // Ferrostar's Android Auto map surface requires API 29.
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    val valhallaUrl =
        localProperties.getProperty("valhallaUrl") ?: "https://valhalla1.openstreetmap.de/route"
    buildConfigField("String", "VALHALLA_URL", "\"$valhallaUrl\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

  // android.util.Log and friends return defaults in JVM unit tests instead of throwing.
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)

  implementation(libs.ferrostar.core)
  implementation(libs.ferrostar.ui.compose)
  implementation(libs.ferrostar.ui.maplibre)
  implementation(libs.ferrostar.google.play.services)
  implementation(libs.ferrostar.car.app)
  implementation(libs.ferrostar.ui.maplibre.car.app)
  implementation(libs.maplibre.compose)
  implementation(libs.car.app.compose)
  implementation(libs.androidx.car.app)
  implementation(libs.play.services.location)

  implementation(platform(libs.okhttp.bom))
  implementation(libs.okhttp)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
