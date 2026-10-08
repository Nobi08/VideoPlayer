plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "com.nobi.player"
  compileSdk = 36
  defaultConfig { applicationId = "com.nobi.player"; minSdk = 24; targetSdk = 36; versionCode = 1; versionName = "1.0" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation("androidx.media3:media3-exoplayer:1.11.1")
  implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
  implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
  implementation("androidx.media3:media3-ui:1.11.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
}
