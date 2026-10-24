import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Backend address. The emulator reaches your laptop at 10.0.2.2; for a real phone put
// diplo.backendUrl=http://<laptop-LAN-IP>:8000 in android/local.properties (not committed).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val backendUrl: String = localProps.getProperty("diplo.backendUrl", "http://10.0.2.2:8000")
// The on-device LLM (LocalBrain): any GGUF file, downloaded once while online. Keyless RunAnywhere
// has an empty catalog, so the app registers this URL itself under the id.
val localModel: String = localProps.getProperty("diplo.localModel", "qwen3-0.6b")
val localModelUrl: String = localProps.getProperty(
    "diplo.localModelUrl",
    "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
)

android {
    namespace = "com.dietcode.diplo"
    // RunAnywhere needs compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dietcode.diplo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "LOCAL_MODEL", "\"$localModel\"")
        buildConfigField("String", "LOCAL_MODEL_URL", "\"$localModelUrl\"")
        // Phones are arm64. RunAnywhere ships native libraries for every ABI, so this keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // RunAnywhere's native libraries are ~200 MB stored raw; compressed, the APK installs much faster
    // over wireless debugging. Android unpacks them once at install.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    // Built-in Kotlin takes its JVM target from here.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 5.x because RunAnywhere pulls in OkHttp 5; okhttp-sse 4.x then crashes on the first event
    // (NoClassDefFoundError: okhttp3.internal.Util). Keep these two on the same version.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")

    // On-device LLM through llama.cpp (LocalBrain), github.com/RunanywhereAI/runanywhere-sdks
    implementation("io.github.sanchitmonga22:runanywhere-sdk:0.20.27")
    implementation("io.github.sanchitmonga22:runanywhere-llamacpp:0.20.27")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json only throws in unit tests; LocalBrainTest parses model replies.
    testImplementation("org.json:json:20240303")
}
