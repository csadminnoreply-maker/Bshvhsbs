package id.agenkode.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------- Model pesan (netral terhadap provider AI) ----------
sealed class Block {
    data class Text(val text: String) : Block()
    data class ToolUse(val id: String, val name: String, val input: JSONObject) : Block()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean) : Block()
}

data class Msg(val role: String, val blocks: List<Block>)

fun Msg.toJson(): JSONObject {
    val arr = JSONArray()
    for (b in blocks) {
        val o = when (b) {
            is Block.Text -> JSONObject().put("t", "text").put("text", b.text)
            is Block.ToolUse -> JSONObject().put("t", "use").put("id", b.id).put("name", b.name).put("input", b.input)
            is Block.ToolResult -> JSONObject().put("t", "res").put("id", b.toolUseId).put("content", b.content).put("err", b.isError)
        }
        arr.put(o)
    }
    return JSONObject().put("role", role).put("blocks", arr)
}

fun msgFromJson(o: JSONObject): Msg {
    val arr = o.getJSONArray("blocks")
    val blocks = ArrayList<Block>()
    for (i in 0 until arr.length()) {
        val b = arr.getJSONObject(i)
        when (b.getString("t")) {
            "text" -> blocks.add(Block.Text(b.getString("text")))
            "use" -> blocks.add(Block.ToolUse(b.getString("id"), b.getString("name"), b.getJSONObject("input")))
            else -> blocks.add(Block.ToolResult(b.getString("id"), b.getString("content"), b.optBoolean("err")))
        }
    }
    return Msg(o.getString("role"), blocks)
}

/** Pastikan setiap tool_use punya tool_result (mis. setelah proses dihentikan paksa). */
fun sanitizeHistory(h: MutableList<Msg>) {
    var i = 0
    while (i < h.size) {
        val m = h[i]
        if (m.role == "assistant") {
            val uses = m.blocks.filterIsInstance<Block.ToolUse>()
            if (uses.isNotEmpty()) {
                val next = h.getOrNull(i + 1)
                val have = next?.blocks?.filterIsInstance<Block.ToolResult>()?.map { it.toolUseId }?.toSet() ?: emptySet()
                val missing = uses.filter { it.id !in have }
                if (missing.isNotEmpty()) {
                    val fix: List<Block> = missing.map { Block.ToolResult(it.id, "Dibatalkan/terputus.", true) }
                    if (next != null && next.role == "user" && have.isNotEmpty()) {
                        h[i + 1] = Msg("user", fix + next.blocks)
                    } else {
                        h.add(i + 1, Msg("user", fix))
                    }
                }
            }
        }
        i++
    }
}

// ---------- Provider AI ----------
data class Preset(val id: String, val label: String, val kind: String, val baseUrl: String, val model: String)
data class ProviderConfig(val id: String, val kind: String, val baseUrl: String, val key: String, val model: String, val label: String)

val PRESETS = listOf(
    Preset("anthropic", "Anthropic (Claude)", "anthropic", "https://api.anthropic.com", "claude-sonnet-5-5"),
    Preset("openai", "OpenAI (GPT)", "openai", "https://api.openai.com/v1", "gpt-4o"),
    Preset("gemini", "Google Gemini", "openai", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    Preset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat"),
    Preset("groq", "Groq", "openai", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    Preset("openrouter", "OpenRouter (ratusan model)", "openai", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4"),
    Preset("mistral", "Mistral", "openai", "https://api.mistral.ai/v1", "mistral-large-latest"),
    Preset("nvidia", "NVIDIA NIM", "openai", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"),
    Preset("cohere", "Cohere", "openai", "https://api.cohere.ai/compatibility/v1", "command-a-03-2025"),
    Preset("custom", "Kustom / Ollama (OpenAI-compatible)", "openai", "", "")
)

class AppSettings(ctx: Context) {
    private val prefs: SharedPreferences = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "secure_prefs", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("plain_prefs", Context.MODE_PRIVATE)
    }

    var active: String
        get() = prefs.getString("active", "anthropic") ?: "anthropic"
        set(v) { prefs.edit().putString("active", v).apply() }
    var ghToken: String
        get() = prefs.getString("gh_token", "") ?: ""
        set(v) { prefs.edit().putString("gh_token", v).apply() }
    var ghRepo: String
        get() = prefs.getString("gh_repo", "agenkode-builds") ?: "agenkode-builds"
        set(v) { prefs.edit().putString("gh_repo", v).apply() }

    fun key(id: String): String = prefs.getString("key_$id", "") ?: ""
    fun setKey(id: String, v: String) { prefs.edit().putString("key_$id", v.trim()).apply() }
    fun model(p: Preset): String = prefs.getString("model_${p.id}", p.model) ?: p.model
    fun setModel(id: String, v: String) { prefs.edit().putString("model_$id", v.trim()).apply() }
    fun baseUrl(p: Preset): String = prefs.getString("url_${p.id}", p.baseUrl) ?: p.baseUrl
    fun setBaseUrl(id: String, v: String) { prefs.edit().putString("url_$id", v.trim()).apply() }

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    fun configFor(id: String): ProviderConfig? {
        val p = PRESETS.firstOrNull { it.id == id } ?: return null
        val k = key(p.id).trim()
        val url = baseUrl(p).trim()
        val m = model(p).trim()
        if (k.isEmpty() && p.id != "custom") return null
        if (url.isEmpty() || m.isEmpty()) return null
        return ProviderConfig(p.id, p.kind, url, k, m, p.label)
    }

    fun activeConfig(): ProviderConfig? = configFor(active)

    /** Urutan provider yang dicoba. Mode otomatis: terbaik untuk coding lebih dulu, sisanya cadangan. */
    fun candidates(): List<ProviderConfig> {
        if (!auto) return listOfNotNull(configFor(active))
        val list = Router.PRIORITY.filter { key(it).isNotBlank() }.mapNotNull { configFor(it) }
        return if (list.isEmpty()) listOfNotNull(configFor(active)) else list
    }

    /** Tempel banyak key sekaligus; provider dikenali dari awalan key. */
    fun importKeys(text: String, overwrite: Boolean): List<String> {
        val done = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val k = line.split(Regex("\s+")).last()
            val id = Router.detect(k) ?: continue
            if (overwrite || key(id).isBlank()) {
                setKey(id, k)
                done.add(PRESETS.first { it.id == id }.label)
            }
        }
        return done
    }
}

// ---------- Proyek ----------
data class ProjectMeta(
    val id: String,
    var name: String,
    var appId: String,
    var appName: String,
    var versionCode: Int,
    var updated: Long
)

class ProjectStore(ctx: Context) {
    private val root = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun files(id: String): File = File(dir(id), "files").apply { mkdirs() }

    fun list(): List<ProjectMeta> =
        (root.listFiles() ?: emptyArray()).mapNotNull { readMeta(it.name) }.sortedByDescending { it.updated }

    fun readMeta(id: String): ProjectMeta? {
        val f = File(dir(id), "meta.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            ProjectMeta(
                id, o.getString("name"), o.optString("appId", "com.agenkode.app"),
                o.optString("appName", "Aplikasi"), o.optInt("versionCode", 0), o.optLong("updated", 0L)
            )
        } catch (e: Exception) { null }
    }

    fun writeMeta(m: ProjectMeta) {
        dir(m.id).mkdirs()
        File(dir(m.id), "meta.json").writeText(
            JSONObject().put("name", m.name).put("appId", m.appId).put("appName", m.appName)
                .put("versionCode", m.versionCode).put("updated", m.updated).toString()
        )
    }

    fun create(name: String): ProjectMeta {
        val id = "p" + System.currentTimeMillis()
        var slug = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(14)
        if (slug.isEmpty() || slug[0].isDigit()) slug = "app$slug"
        val clean = name.trim().take(40)
        val m = ProjectMeta(
            id, clean, "com.agenkode.$slug${(100..999).random()}",
            clean.filter { it.isLetterOrDigit() || it == ' ' }.take(24).ifBlank { "Aplikasi" },
            0, System.currentTimeMillis()
        )
        files(id)
        writeMeta(m)
        return m
    }

    fun delete(id: String) { dir(id).deleteRecursively() }

    fun loadChat(id: String): MutableList<Msg> {
        val f = File(dir(id), "chat.json")
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { msgFromJson(a.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveChat(id: String, list: List<Msg>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        File(dir(id), "chat.json").writeText(a.toString())
    }
}
