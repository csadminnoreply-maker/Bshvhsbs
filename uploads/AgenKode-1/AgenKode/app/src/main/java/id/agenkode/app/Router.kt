package id.agenkode.app

import kotlinx.coroutines.CancellationException

/** Memilih provider otomatis + cadangan. */
object Router {
    /** Urutan rekomendasi untuk tugas coding (kualitas tool-calling terbaik lebih dulu). */
    val PRIORITY = listOf("anthropic", "openai", "gemini", "openrouter", "deepseek", "mistral", "nvidia", "groq", "cohere")

    private val cooldown = HashMap<String, Long>()

    fun detect(raw: String): String? {
        val k = raw.trim()
        return when {
            k.startsWith("sk-ant-") -> "anthropic"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("gsk_") -> "groq"
            k.startsWith("nvapi-") -> "nvidia"
            k.startsWith("mstrl_") -> "mistral"
            k.startsWith("cohere_") -> "cohere"
            k.startsWith("sk-") -> "openai"
            k.startsWith("AIza") || k.startsWith("AQ.") -> "gemini"
            else -> null
        }
    }

    fun available(id: String, now: Long): Boolean = (cooldown[id] ?: 0L) <= now

    /** Provider yang gagal dilewati sementara agar tidak mengulang error yang sama. */
    fun penalize(id: String, code: Int) {
        val ms = when (code) {
            401, 403, 404 -> 3_600_000L
            429 -> 180_000L
            else -> 60_000L
        }
        cooldown[id] = System.currentTimeMillis() + ms
    }

    fun reset() = cooldown.clear()

    suspend fun test(cfg: ProviderConfig): String = try {
        makeLlm(cfg).chat("Balas sangat singkat.", listOf(Msg("user", listOf(Block.Text("Balas hanya: OK")))), emptyList())
        "OK"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (e.message ?: "error").lineSequence().first().take(120)
    }
}

class FallbackLlm(private val cfgs: List<ProviderConfig>, private val onSwitch: (String) -> Unit) : Llm {
    override suspend fun chat(system: String, msgs: List<Msg>, tools: List<ToolDef>): LlmReply {
        val now = System.currentTimeMillis()
        val ready = cfgs.filter { Router.available(it.id, now) }.ifEmpty { cfgs }
        val errors = ArrayList<String>()
        for ((i, c) in ready.withIndex()) {
            try {
                if (i > 0) onSwitch(c.label)
                return makeLlm(c).chat(system, msgs, tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                Router.penalize(c.id, e.code)
                errors.add("${c.label}: ${(e.message ?: "").lineSequence().first()}")
            }
        }
        throw LlmException("Semua provider gagal:\n" + errors.joinToString("\n"))
    }
}
