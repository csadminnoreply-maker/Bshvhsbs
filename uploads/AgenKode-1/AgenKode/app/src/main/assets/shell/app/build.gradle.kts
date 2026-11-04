plugins {
    id("com.android.application")
}

val vc = providers.gradleProperty("verCode").getOrElse("1").toInt()
val ks = providers.gradleProperty("ksFile").orNull

android {
    namespace = "app.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = providers.gradleProperty("appId").getOrElse("com.example.myapp")
        minSdk = 24
        targetSdk = 34
        versionCode = vc
        versionName = "1.0.$vc"
        resValue("string", "app_name", providers.gradleProperty("appName").getOrElse("Aplikasi"))
    }
    if (ks != null) {
        signingConfigs {
            create("main") {
                storeFile = file(ks)
                storePassword = "android123"
                keyAlias = "main"
                keyPassword = "android123"
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
