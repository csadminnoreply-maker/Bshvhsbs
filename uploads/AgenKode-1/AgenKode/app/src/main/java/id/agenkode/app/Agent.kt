package id.agenkode.app

import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

sealed class AgentEvent {
    data class Text(val text: String) : AgentEvent()
    data class Status(val text: String) : AgentEvent()
    data class ToolStart(val id: String, val name: String, val input: JSONObject) : AgentEvent()
    data class ToolEnd(val id: String, val name: String, val result: String, val isError: Boolean) : AgentEvent()
}

private const val PROMPT = """You are AgenKode, an expert autonomous app-building agent running inside an Android app. The user chats with you (usually in Indonesian: always reply in the user's language) to create, fix and ship small apps. You work on a project folder stored on the phone, using tools. You cannot run shell commands.

# How projects work
- Default project type = WEB APP: index.html in the project root plus css/js files. Use RELATIVE paths only (./style.css, js/app.js), never absolute (/style.css). The user can preview it instantly; build_apk wraps the folder in a WebView app and builds an installable APK in the cloud (GitHub Actions).
- Use plain HTML/CSS/JavaScript (no npm, no build step). Prefer zero external dependencies so the app works offline. Only if a library is truly needed, load a pinned version from cdnjs/jsDelivr and tell the user it needs internet.
- Only create a native Android (Kotlin) project if the user explicitly needs native features a web app cannot offer. Native layout: settings.gradle.kts at the root; AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 24; do NOT create gradlew or wrapper files; text files only (no binary assets).

# Working method
1. Understand the request. Ask a clarifying question only if it is truly ambiguous; otherwise choose sensible defaults, state them briefly, and proceed.
2. For anything beyond a trivial change call update_plan first (3-7 short steps) and keep it updated (pending / in_progress / done).
3. Existing project? list_files, then read_file the relevant files BEFORE changing them. Never edit blind.
4. New files: write_file. Small changes: edit_file (old_str must match exactly and be unique). Keep each file under ~250 lines: split into index.html, style.css, app.js, etc. Very long single outputs get truncated.
5. After creating or changing a web app ALWAYS run check_web_app. If it reports problems, fix the root cause and run it again until clean. Static warnings after write_file are hints; check_web_app is authoritative. It cannot test taps, so also re-read tricky logic yourself.
6. Before the first build call set_app_info (unique app_id such as com.agenkode.todo and a short app_name). Call build_apk only when the user asks to build/install, or when the app is finished and verified. Do not build after every tiny change.
7. If build_apk fails: read the log, find the FIRST real error (not trailing noise), fix the cause, rebuild. Never retry unchanged. After 4 failed builds stop and explain.
8. Be honest. Never claim something works unless a tool result shows it. If a tool fails, say so and adapt.

# Quality bar
Mobile-first UI: viewport meta, touch targets >= 44px, readable fonts, comfortable spacing, no horizontal scrolling, dark mode via prefers-color-scheme, clear empty states. Persist user data in localStorage. Validate input and handle errors gracefully. Modern clean visual style: consistent palette, rounded corners, subtle transitions. Give the app a clear title.

# Replies
Short and clear. While working avoid long narration; let tool calls speak. At the end give a brief summary: what was made, how to use it, and the next step (for example: tap Pratinjau to try it, or ask for the APK). Never paste whole files into chat. Never ask the user to type API keys or secrets in chat. Refuse to build malware, spyware or apps meant to deceive or harm people."""

object Agent {
    private const val MAX_STEPS = 40

    suspend fun run(llm: Llm, history: MutableList<Msg>, userText: String, tc: ToolContext, emit: (AgentEvent) -> Unit) {
        history.add(Msg("user", listOf(Block.Text(userText))))
        tc.buildFailures = 0
        val failCounts = HashMap<String, Int>()
        var steps = 0

        while (true) {
            coroutineContext.ensureActive()
            steps++
            if (steps > MAX_STEPS) {
                emit(AgentEvent.Text("Berhenti sementara: sudah $MAX_STEPS langkah dalam satu permintaan. Ketik \"lanjut\" untuk meneruskan."))
                return
            }
            compact(history)
            emit(AgentEvent.Status(if (steps == 1) "AI sedang berpikir…" else "AI menganalisis hasil…"))
            val reply = llm.chat(systemPrompt(tc), history, Tools.defs)

            if (reply.blocks.isEmpty()) {
                emit(AgentEvent.Text("(AI tidak memberi jawaban. Coba kirim ulang atau ganti model.)"))
                return
            }
            history.add(Msg("assistant", reply.blocks))
            reply.blocks.filterIsInstance<Block.Text>().filter { it.text.isNotBlank() }.forEach { emit(AgentEvent.Text(it.text)) }

            val uses = reply.blocks.filterIsInstance<Block.ToolUse>()
            if (uses.isEmpty()) {
                if (reply.stop == "length") emit(AgentEvent.Text("Jawaban terpotong karena terlalu panjang. Ketik \"lanjut\"."))
                return
            }

            val results = ArrayList<Block>()
            try {
                if (reply.stop == "length") {
                    emit(AgentEvent.Status("Output terpotong, meminta AI memecah pekerjaan…"))
                    for (u in uses) {
                        results.add(
                            Block.ToolResult(
                                u.id,
                                "Output terpotong karena terlalu panjang, aksi TIDAK dijalankan. Pecah menjadi file-file lebih kecil (< 250 baris per file) lalu ulangi.",
                                true
                            )
                        )
                    }
                } else {
                    for (u in uses) {
                        coroutineContext.ensureActive()
                        emit(AgentEvent.ToolStart(u.id, u.name, u.input))
                        val key = u.name + u.input.toString()
                        if (u.name == "build_apk" && (tc.meta.appId.isNullOrEmpty() || tc.meta.appName.isNullOrEmpty())) {
                            emit(AgentEvent.Text("[SISTEM] app_id atau app_name belum diatur. Memanggil set_app_info terlebih dahulu..."))
                            tc.tools.set_app_info(
                                mapOf(
                                    "app_id" to "com.agenkode.${tc.meta.name.lowercase()}",
                                    "app_name" to tc.meta.name.replaceFirstChar { it.lowercase() }
                                )
                            )
                        }
                        val out = Tools.run(u.name, u.input, tc)
                        var text = out.text
                        if (out.isError) {
                            val n = (failCounts[key] ?: 0) + 1
                            failCounts[key] = n
                            if (n >= 2) {
                                text += "\n\n[SISTEM] Kegagalan identik ke-$n. Jangan ulangi aksi yang sama; baca ulang file/log, pahami penyebabnya, lalu ubah pendekatan."
                            }
                        } else {
                            failCounts.remove(key)
                        }
                        results.add(Block.ToolResult(u.id, text, out.isError))
                        emit(AgentEvent.ToolEnd(u.id, u.name, text, out.isError))
                    }
                }
            } finally {
                for (u in uses.drop(results.size)) results.add(Block.ToolResult(u.id, "Dibatalkan.", true))
                history.add(Msg("user", results))
            }

            if (failCounts.values.any { it >= 4 }) {
                emit(AgentEvent.Text("Dihentikan: aksi yang sama gagal berulang kali. Jelaskan masalahnya atau beri petunjuk tambahan."))
                return
            }
        }
    }

    private fun systemPrompt(tc: ToolContext): String {
        val files = tc.root.walkTopDown().filter { it.isFile }.take(40)
            .joinToString("\n") { "- " + it.relativeTo(tc.root).path.replace('\\', '/') }
        return PROMPT + "\n\n# Konteks proyek saat ini\n" +
            "- Nama proyek: ${tc.meta.name}\n" +
            "- app_id: ${tc.meta.appId}\n- app_name: ${tc.meta.appName}\n" +
            "- Versi build terakhir: ${tc.meta.versionCode}\n" +
            "- File (maks 40):\n" + files.ifEmpty { "(kosong)" }
    }

    /** Hemat konteks: pangkas hasil tool dan isi file lama yang berukuran besar. */
    private fun compact(h: MutableList<Msg>) {
        fun size(m: Msg): Int = m.blocks.sumOf { b ->
            when (b) {
                is Block.Text -> b.text.length
                is Block.ToolUse -> b.input.toString().length
                is Block.ToolResult -> b.content.length
            }
        }
        if (h.sumOf { size(it) } < 150_000) return
        val limit = h.size - 8
        for (i in 0 until limit) {
            val m = h[i]
            val nb: List<Block> = m.blocks.map { b ->
                when (b) {
                    is Block.ToolResult ->
                        if (b.content.length > 500) b.copy(content = b.content.take(300) + "\n…[dipangkas untuk hemat konteks]") else b
                    is Block.ToolUse ->
                        if (b.input.toString().length > 600) {
                            val o = JSONObject(b.input.toString())
                            if (o.has("content")) o.put("content", "[isi dipangkas; ada di file]")
                            if (o.has("old_str")) o.put("old_str", "…")
                            if (o.has("new_str")) o.put("new_str", "…")
                            Block.ToolUse(b.id, b.name, o)
                        } else b
                    is Block.Text -> b
                }
            }
            h[i] = Msg(m.role, nb)
        }
    }
}
