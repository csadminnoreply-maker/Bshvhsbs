package id.agenkode.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LlmException(message: String, val code: Int = 0) : Exception(message)

data class ToolDef(val name: String, val description: String, val schema: JSONObject)

/** stop: "end" (selesai), "tool" (minta jalankan tool), "length" (terpotong). */
data class LlmReply(val blocks: List<Block>, val stop: String)

interface Llm {
    suspend fun chat(system: String, msgs: List<Msg>, tools: List<ToolDef>): LlmReply
}

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    fun jsonBody(o: JSONObject) = o.toString().toRequestBody(JSON)

    suspend fun send(req: Request): Pair<Int, String> {
        val resp = suspendCancellableCoroutine<Response> { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }
        return withContext(Dispatchers.IO) { resp.use { it.code to (it.body?.string() ?: "") } }
    }
}

private fun friendlyError(code: Int, body: String): String {
    val detail = try {
        val o = JSONObject(body)
        when (val e = o.opt("error")) {
            is JSONObject -> e.optString("message")
            is String -> e
            else -> o.optString("message")
        }
    } catch (e: Exception) { body.take(300) }
    val head = when (code) {
        401, 403 -> "API key ditolak ($code). Periksa key di Pengaturan."
        404 -> "Model atau URL tidak ditemukan (404). Periksa nama model dan base URL di Pengaturan."
        429 -> "Kena batas pemakaian atau kuota habis (429)."
        400 -> "Permintaan ditolak (400)."
        else -> "Error server ($code)."
    }
    return if (detail.isNullOrBlank()) head else "$head\n$detail".take(700)
}

/** POST JSON dengan retry otomatis untuk gangguan jaringan / 429 / 5xx. */
private suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject): String {
    var attempt = 0
    while (true) {
        attempt++
        val rb = Request.Builder().url(url).post(Net.jsonBody(body))
        headers.forEach { (k, v) -> rb.header(k, v) }
        val result = try {
            Net.send(rb.build())
        } catch (e: IOException) {
            if (attempt < 3) { delay(2000L * attempt); continue }
            throw LlmException("Koneksi gagal: ${e.message ?: "periksa internet Anda"}", -1)
        }
        val (code, text) = result
        if (code in 200..299) return text
        val retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 529
        if (retryable && attempt < 3) { delay(2000L * attempt); continue }
        throw LlmException(friendlyError(code, text), code)
    }
}

private fun cache() = JSONObject().put("type", "ephemeral")

class AnthropicLlm(private val baseUrl: String, private val key: String, private val model: String) : Llm {
    override suspend fun chat(system: String, msgs: List<Msg>, tools: List<ToolDef>): LlmReply {
        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", 8192)
        body.put("system", JSONArray().put(JSONObject().put("type", "text").put("text", system).put("cache_control", cache())))

        val ta = JSONArray()
        tools.forEachIndexed { i, t ->
            val o = JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.schema)
            if (i == tools.lastIndex) o.put("cache_control", cache())
            ta.put(o)
        }
        if (tools.isNotEmpty()) body.put("tools", ta)

        val ma = JSONArray()
        msgs.forEachIndexed { idx, m ->
            val content = JSONArray()
            for (b in m.blocks) {
                when (b) {
                    is Block.Text -> if (b.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", b.text))
                    is Block.ToolUse -> content.put(JSONObject().put("type", "tool_use").put("id", b.id).put("name", b.name).put("input", b.input))
                    is Block.ToolResult -> content.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", b.toolUseId)
                            .put("content", b.content.ifBlank { "(kosong)" }).put("is_error", b.isError)
                    )
                }
            }
            if (content.length() == 0) content.put(JSONObject().put("type", "text").put("text", "(kosong)"))
            if (idx == msgs.lastIndex) content.getJSONObject(content.length() - 1).put("cache_control", cache())
            ma.put(JSONObject().put("role", m.role).put("content", content))
        }
        body.put("messages", ma)

        val base = baseUrl.trimEnd('/').removeSuffix("/v1")
        val txt = postJson(
            "$base/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
            body
        )
        val o = JSONObject(txt)
        val arr = o.getJSONArray("content")
        val blocks = ArrayList<Block>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            when (c.getString("type")) {
                "text" -> blocks.add(Block.Text(c.getString("text")))
                "tool_use" -> blocks.add(Block.ToolUse(c.getString("id"), c.getString("name"), c.optJSONObject("input") ?: JSONObject()))
            }
        }
        val stop = when (o.optString("stop_reason")) {
            "tool_use" -> "tool"
            "max_tokens" -> "length"
            else -> "end"
        }
        return LlmReply(blocks, stop)
    }
}

/** Untuk OpenAI, Gemini, DeepSeek, Groq, Mistral, OpenRouter, Ollama, dll. */
class OpenAiLlm(private val baseUrl: String, private val key: String, private val model: String) : Llm {
    override suspend fun chat(system: String, msgs: List<Msg>, tools: List<ToolDef>): LlmReply {
        val ms = JSONArray()
        ms.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) {
            if (m.role == "assistant") {
                val text = m.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                val uses = m.blocks.filterIsInstance<Block.ToolUse>()
                val o = JSONObject().put("role", "assistant")
                o.put("content", if (text.isBlank()) JSONObject.NULL else text)
                if (uses.isNotEmpty()) {
                    val tc = JSONArray()
                    uses.forEach {
                        tc.put(
                            JSONObject().put("id", it.id).put("type", "function")
                                .put("function", JSONObject().put("name", it.name).put("arguments", it.input.toString()))
                        )
                    }
                    o.put("tool_calls", tc)
                }
                ms.put(o)
            } else {
                for (b in m.blocks) {
                    when (b) {
                        is Block.ToolResult -> ms.put(
                            JSONObject().put("role", "tool").put("tool_call_id", b.toolUseId)
                                .put("content", b.content.ifBlank { "(kosong)" })
                        )
                        is Block.Text -> if (b.text.isNotBlank()) ms.put(JSONObject().put("role", "user").put("content", b.text))
                        else -> {}
                    }
                }
            }
        }
        val ta = JSONArray()
        tools.forEach {
            ta.put(
                JSONObject().put("type", "function").put(
                    "function", JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema)
                )
            )
        }
        val body = JSONObject().put("model", model).put("messages", ms)
        if (tools.isNotEmpty()) body.put("tools", ta)
        if (!baseUrl.contains("api.openai.com")) body.put("max_tokens", 8192)

        val headers = HashMap<String, String>()
        if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
        val txt = postJson(baseUrl.trimEnd('/') + "/chat/completions", headers, body)

        val o = JSONObject(txt)
        val choice = o.getJSONArray("choices").getJSONObject(0)
        val msg = choice.getJSONObject("message")
        val blocks = ArrayList<Block>()
        val content = if (msg.isNull("content")) "" else msg.getString("content")
        if (content.isNotBlank()) blocks.add(Block.Text(content))
        val tcs = msg.optJSONArray("tool_calls")
        if (tcs != null) {
            for (i in 0 until tcs.length()) {
                val t = tcs.getJSONObject(i)
                val f = t.getJSONObject("function")
                val id = t.optString("id").ifBlank { "call_${System.nanoTime()}_$i" }
                val args = f.optString("arguments", "{}")
                val input = try {
                    JSONObject(if (args.isBlank()) "{}" else args)
                } catch (e: Exception) {
                    JSONObject().put("_parse_error", "Argumen JSON tidak valid atau terpotong")
                }
                blocks.add(Block.ToolUse(id, f.getString("name"), input))
            }
        }
        val finish = choice.optString("finish_reason")
        val stop = when {
            finish == "length" -> "length"
            blocks.any { it is Block.ToolUse } -> "tool"
            else -> "end"
        }
        return LlmReply(blocks, stop)
    }
}

fun makeLlm(cfg: ProviderConfig): Llm =
    if (cfg.kind == "anthropic") AnthropicLlm(cfg.baseUrl, cfg.key, cfg.model)
    else OpenAiLlm(cfg.baseUrl, cfg.key, cfg.model)
