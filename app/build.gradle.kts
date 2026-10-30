plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.builder"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.builder.apkbuilder"
        minSdk = 24
        // 28: Android разрешает запускать скачанные бинарники (aapt2 и др.) из папки приложения.
        // Для targetSdk >= 29 это запрещено системой — не повышайте без отдельного решения.
        targetSdk = 28
        // На CI номер растёт с каждым запуском, поэтому свежая сборка ставится поверх старой.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3
        versionName = "2.1"
    }
    signingConfigs {
        // Постоянный debug-ключ в репозитории: все сборки подписаны одинаково,
        // иначе Android не даёт обновить приложение («приложение не установлено»).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
