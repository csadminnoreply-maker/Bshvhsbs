package id.agenkode.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class Agent {
    val context: Context
    val meta: ProjectMeta
    val tools: Tools

    constructor(context: Context) {
        this.context = context
        this.meta = ProjectMeta()
        this.tools = Tools(context, meta)
        this.tools.loadMeta()
    }

    fun set_app_info(info: Map<String, String>) {
        tools.set_app_info(info)
    }

    fun build_apk() {
        if (meta.appId.isEmpty() || meta.appName.isEmpty()) {
            Log.w("Agent", "app_id atau app_name belum diatur. Memanggil set_app_info terlebih dahulu...")
            val defaultInfo = mapOf(
                "app_id" to "com.agenkode.${meta.name.lowercase()}",
                "app_name" to meta.name.replaceFirstChar { it.lowercase() }
            )
            set_app_info(defaultInfo)
        }
        tools.build_apk()
    }

    fun check_web_app() {
        tools.check_web_app()
    }
}