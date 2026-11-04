plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

task clean(type: Delete) {
    delete rootProject.buildDir
}