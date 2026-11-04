package id.agenkode.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class ProjectMeta {
    var name: String = ""
    var appId: String = "com.agenkode.app"
    var appName: String = "Aplikasi"
    var versionCode: Int = 1
    var versionName: String = "1.0"
    var packageName: String = "com.agenkode.app"
    var buildType: String = "debug"
    var isWebApp: Boolean = false
    var webAppUrl: String = ""
    var webAppName: String = ""
    var webAppDescription: String = ""
    var webAppAuthor: String = ""
    var webAppAuthorUrl: String = ""
    var webAppKeywords: String = ""
    var webAppThemeColor: String = "#ffffff"
    var webAppBackgroundColor: String = "#ffffff"
    var webAppDisplay: String = "standalone"
    var webAppOrientation: String = "any"
    var webAppCapable: String = ""
    var webAppStartUrl: String = "/"
    var webAppIcon: String = ""
    var webAppSplashScreen: String = ""
    var webAppManifest: String = ""
    var webAppServiceWorker: String = ""
    var webAppOfflineEnabled: Boolean = false
    var webAppCacheEnabled: Boolean = false
    var webAppIndex: String = ""
    var webAppAssets: List<String> = emptyList()
    var webAppExcludes: List<String> = emptyList()
    var webAppIncludes: List<String> = emptyList()
    var webAppFeatures: List<String> = emptyList()
    var webAppPermissions: List<String> = emptyList()
    var webAppUses: List<String> = emptyList()

    fun reset() {
        appId = "com.agenkode.app"
        appName = "Aplikasi"
        versionCode = 1
        versionName = "1.0"
        packageName = "com.agenkode.app"
        buildType = "debug"
        isWebApp = false
        webAppUrl = ""
        webAppName = ""
        webAppDescription = ""
        webAppAuthor = ""
        webAppAuthorUrl = ""
        webAppKeywords = ""
        webAppThemeColor = "#ffffff"
        webAppBackgroundColor = "#ffffff"
        webAppDisplay = "standalone"
        webAppOrientation = "any"
        webAppCapable = ""
        webAppStartUrl = "/"
        webAppIcon = ""
        webAppSplashScreen = ""
        webAppManifest = ""
        webAppServiceWorker = ""
        webAppOfflineEnabled = false
        webAppCacheEnabled = false
        webAppIndex = ""
        webAppAssets = emptyList()
        webAppExcludes = emptyList()
        webAppIncludes = emptyList()
        webAppFeatures = emptyList()
        webAppPermissions = emptyList()
        webAppUses = emptyList()
    }
}