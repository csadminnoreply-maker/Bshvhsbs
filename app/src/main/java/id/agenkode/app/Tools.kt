package id.agenkode.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class Tools {
    private val context: Context
    private val meta: ProjectMeta

    constructor(context: Context, meta: ProjectMeta) {
        this.context = context
        this.meta = meta
    }

    fun set_app_info(info: Map<String, String>) {
        if (info.containsKey("app_id")) {
            val appId = info["app_id"] ?: ""
            if (appId.isNotEmpty()) {
                meta.appId = appId
            }
        }
        if (info.containsKey("app_name")) {
            val appName = info["app_name"] ?: ""
            if (appName.isNotEmpty()) {
                meta.appName = appName
            }
        }
        saveMeta()
    }

    fun saveMeta() {
        try {
            val json = JSONObject()
            json.put("appId", meta.appId)
            json.put("appName", meta.appName)
            json.put("versionCode", meta.versionCode)
            json.put("versionName", meta.versionName)
            json.put("packageName", meta.packageName)
            json.put("buildType", meta.buildType)

            val file = File(context.filesDir, "meta.json")
            val writer = OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8)
            writer.write(json.toString())
            writer.close()
        } catch (e: Exception) {
            Log.e("Tools", "Failed to save meta", e)
        }
    }

    fun loadMeta() {
        try {
            val file = File(context.filesDir, "meta.json")
            if (file.exists()) {
                val json = JSONObject(file.readText())
                meta.appId = json.optString("appId", "com.agenkode.app")
                meta.appName = json.optString("appName", "Aplikasi")
                meta.versionCode = json.optInt("versionCode", 1)
                meta.versionName = json.optString("versionName", "1.0")
                meta.packageName = json.optString("packageName", "com.agenkode.app")
                meta.buildType = json.optString("buildType", "debug")
            }
        } catch (e: Exception) {
            Log.e("Tools", "Failed to load meta", e)
        }
    }

    fun build_apk() {
        // Placeholder for APK build logic
        Log.d("Tools", "Building APK...")
    }

    fun check_web_app() {
        // Placeholder for web app check logic
        Log.d("Tools", "Checking web app...")
    }
}