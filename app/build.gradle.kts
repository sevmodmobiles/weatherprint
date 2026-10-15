plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.home.weatherprint"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.home.weatherprint"
        minSdk = 26
        targetSdk = 34
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        create("release") {
            // Ключ лежит в репозитории, чтобы любая сборка (GitHub Actions или Android Studio)
            // подписывалась одинаково и обновления ставились поверх старой версии.
            storeFile = file("signing.keystore")
            storePassword = "QYi4rvNH463K3bZrSKvrUNgv"
            keyAlias = "weatherprint"
            keyPassword = "QYi4rvNH463K3bZrSKvrUNgv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
