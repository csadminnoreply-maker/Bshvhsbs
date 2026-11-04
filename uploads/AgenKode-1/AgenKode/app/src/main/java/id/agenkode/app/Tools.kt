package id.agenkode.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

data class ToolOutcome(val text: String, val isError: Boolean = false)
data class PlanItem(val text: String, val status: String)

fun parsePlan(o: JSONObject): List<PlanItem> {
    val a = o.optJSONArray("steps") ?: return emptyList()
    val out = ArrayList<PlanItem>()
    for (i in 0 until a.length()) {
        val s = a.optJSONObject(i) ?: continue
        out.add(PlanItem(s.optString("text"), s.optString("status", "pending")))
    }
    return out
}

class ToolContext(
    val ctx: Context,
    val store: ProjectStore,
    val settings: AppSettings,
    val meta: ProjectMeta,
    val progress: (String) -> Unit,
    val onPlan: (List<PlanItem>) -> Unit
) {
    val root: File get() = store.files(meta.id).canonicalFile
    var buildFailures = 0
}

private fun strp(d: String) = JSONObject().put("type", "string").put("description", d)
private fun intp(d: String) = JSONObject().put("type", "integer").put("description", d)
private fun boolp(d: String) = JSONObject().put("type", "boolean").put("description", d)
private fun schema(props: Map<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
    val p = JSONObject()
    props.forEach { (k, v) -> p.put(k, v) }
    return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
}

private val TEXT_EXT = setOf(
    "html", "htm", "css", "js", "mjs", "json", "svg", "txt", "md", "xml", "kt", "kts",
    "java", "gradle", "properties", "pro", "yml", "yaml", "csv", "webmanifest"
)

object Tools {
    private val planStep = JSONObject().put("type", "object")
        .put(
            "properties", JSONObject()
                .put("text", strp("Langkah singkat"))
                .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "done"))))
        )
        .put("required", JSONArray(listOf("text", "status")))

    val defs: List<ToolDef> = listOf(
        ToolDef(
            "list_files", "Tampilkan semua file proyek beserta ukurannya. Panggil di awal untuk memahami proyek.",
            schema(mapOf("path" to strp("Sub-folder (opsional)")))
        ),
        ToolDef(
            "read_file", "Baca file dengan nomor baris. Default 400 baris pertama; gunakan start_line/end_line untuk bagian lain.",
            schema(
                mapOf("path" to strp("Path relatif"), "start_line" to intp("Baris awal (1-based)"), "end_line" to intp("Baris akhir")),
                listOf("path")
            )
        ),
        ToolDef(
            "write_file", "Buat file baru atau timpa seluruh isinya. Jaga tiap file di bawah ~250 baris; pecah menjadi beberapa file.",
            schema(mapOf("path" to strp("Path relatif, mis. index.html atau js/app.js"), "content" to strp("Isi file lengkap")), listOf("path", "content"))
        ),
        ToolDef(
            "edit_file", "Ganti teks di file yang sudah ada. old_str harus persis sama dan unik (kecuali replace_all). Untuk perubahan kecil.",
            schema(
                mapOf(
                    "path" to strp("Path relatif"), "old_str" to strp("Teks lama, persis"), "new_str" to strp("Teks pengganti"),
                    "replace_all" to boolp("Ganti semua kemunculan")
                ),
                listOf("path", "old_str", "new_str")
            )
        ),
        ToolDef("delete_file", "Hapus file atau folder.", schema(mapOf("path" to strp("Path relatif")), listOf("path"))),
        ToolDef(
            "search_files", "Cari teks (tanpa membedakan huruf besar/kecil) di semua file proyek.",
            schema(mapOf("query" to strp("Teks yang dicari"), "path" to strp("Sub-folder (opsional)")), listOf("query"))
        ),
        ToolDef(
            "check_web_app",
            "Jalankan index.html di WebView tersembunyi ±4 detik dan laporkan error JavaScript/console serta file yang gagal dimuat. WAJIB dipanggil setelah membuat/mengubah aplikasi web.",
            schema(emptyMap())
        ),
        ToolDef(
            "set_app_info", "Atur identitas APK. app_id huruf kecil dipisah titik (mis. com.agenkode.catatan); app_name nama yang tampil di HP.",
            schema(mapOf("app_id" to strp("Application ID"), "app_name" to strp("Nama aplikasi")))
        ),
        ToolDef(
            "update_plan", "Tampilkan/perbarui rencana kerja ke pengguna (3-7 langkah singkat).",
            schema(
                mapOf("steps" to JSONObject().put("type", "array").put("description", "Daftar langkah").put("items", planStep)),
                listOf("steps")
            )
        ),
        ToolDef(
            "build_apk",
            "Bangun proyek menjadi APK di cloud (GitHub Actions) lalu unduh. Butuh 2-6 menit. Jika gagal, hasilnya berisi log error untuk diperbaiki.",
            schema(emptyMap())
        )
    )

    suspend fun run(name: String, input: JSONObject, tc: ToolContext): ToolOutcome {
        if (input.has("_parse_error")) {
            return ToolOutcome("Argumen tool tidak valid atau terpotong. Kirim ulang dengan JSON valid dan isi lebih pendek.", true)
        }
        return try {
            when (name) {
                "list_files" -> io { listFiles(tc, input.optString("path", "")) }
                "read_file" -> io { readFile(tc, input) }
                "write_file" -> io { writeFile(tc, input) }
                "edit_file" -> io { editFile(tc, input) }
                "delete_file" -> io { deleteFile(tc, input.optString("path")) }
                "search_files" -> io { searchFiles(tc, input) }
                "check_web_app" -> checkWebApp(tc)
                "set_app_info" -> io { setAppInfo(tc, input) }
                "update_plan" -> {
                    tc.onPlan(parsePlan(input))
                    ToolOutcome("Rencana dicatat. Lanjutkan mengerjakan langkah berikutnya.")
                }
                "build_apk" -> buildApk(tc)
                else -> ToolOutcome("Tool tidak dikenal: $name", true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome("Error: ${e.message ?: e.toString()}", true)
        }
    }

    private suspend fun io(block: () -> ToolOutcome): ToolOutcome = withContext(Dispatchers.IO) { block() }

    private fun resolve(root: File, rel: String): File {
        val f = File(root, rel.trim().trimStart('/', '\\')).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("Path di luar proyek: $rel")
        }
        return f
    }

    private fun rel(root: File, f: File) = f.relativeTo(root).path.replace('\\', '/')

    private fun listFiles(tc: ToolContext, path: String): ToolOutcome {
        val root = tc.root
        val dir = resolve(root, path)
        if (!dir.exists()) return ToolOutcome("Folder tidak ada: $path", true)
        val all = dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        if (all.isEmpty()) return ToolOutcome("(proyek kosong)")
        val sb = StringBuilder()
        all.take(300).forEach { sb.append(rel(root, it)).append("  (").append(it.length()).append(" B)\n") }
        if (all.size > 300) sb.append("... dan ${all.size - 300} file lain")
        return ToolOutcome(sb.toString())
    }

    private fun readFile(tc: ToolContext, input: JSONObject): ToolOutcome {
        val path = input.optString("path")
        val f = resolve(tc.root, path)
        if (!f.isFile) return ToolOutcome("File tidak ada: $path. Gunakan list_files.", true)
        val lines = f.readText().lines()
        val s = input.optInt("start_line", 1).coerceAtLeast(1)
        if (s > lines.size) return ToolOutcome("File hanya punya ${lines.size} baris.", true)
        val e = input.optInt("end_line", s + 399).coerceAtMost(lines.size).coerceAtLeast(s)
        val sb = StringBuilder()
        var last = s - 1
        for (i in s..e) {
            sb.append(i).append('\t').append(lines[i - 1]).append('\n')
            last = i
            if (sb.length > 40_000) break
        }
        if (last < lines.size) sb.append("[... baris ${last + 1}-${lines.size} belum ditampilkan; gunakan start_line/end_line]")
        return ToolOutcome(sb.toString())
    }

    private fun writeFile(tc: ToolContext, input: JSONObject): ToolOutcome {
        val path = input.optString("path")
        if (path.isBlank()) return ToolOutcome("Parameter path wajib.", true)
        if (!input.has("content")) return ToolOutcome("Parameter content wajib.", true)
        val content = input.optString("content")
        if (content.length > 300_000) return ToolOutcome("File terlalu besar (maks 300 KB). Pecah menjadi beberapa file.", true)
        val f = resolve(tc.root, path)
        if (f.isDirectory) return ToolOutcome("$path adalah folder.", true)
        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(content)
        val warn = Validator.check(f, content)
        return ToolOutcome((if (existed) "Menimpa " else "Membuat ") + "$path (${content.lines().size} baris)." + warn)
    }

    private fun editFile(tc: ToolContext, input: JSONObject): ToolOutcome {
        val path = input.optString("path")
        val f = resolve(tc.root, path)
        if (!f.isFile) return ToolOutcome("File tidak ada: $path", true)
        val old = input.optString("old_str")
        val new = input.optString("new_str")
        val all = input.optBoolean("replace_all", false)
        if (old.isEmpty()) return ToolOutcome("old_str tidak boleh kosong.", true)
        val text = f.readText()
        var count = 0
        var idx = text.indexOf(old)
        while (idx >= 0) { count++; idx = text.indexOf(old, idx + old.length) }
        if (count == 0) {
            val hint = if (text.contains(old.trim())) " Ada teks mirip tetapi spasi/indentasi berbeda." else ""
            return ToolOutcome("old_str tidak ditemukan.$hint Baca ulang file dengan read_file lalu salin teks persis.", true)
        }
        if (count > 1 && !all) {
            return ToolOutcome("old_str ditemukan $count kali. Tambahkan konteks sekitar agar unik, atau set replace_all=true.", true)
        }
        val updated = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        f.writeText(updated)
        return ToolOutcome("Mengedit $path ($count penggantian)." + Validator.check(f, updated))
    }

    private fun deleteFile(tc: ToolContext, path: String): ToolOutcome {
        val f = resolve(tc.root, path)
        if (f == tc.root) return ToolOutcome("Tidak boleh menghapus root proyek.", true)
        if (!f.exists()) return ToolOutcome("Tidak ada: $path", true)
        f.deleteRecursively()
        return ToolOutcome("Dihapus: $path")
    }

    private fun searchFiles(tc: ToolContext, input: JSONObject): ToolOutcome {
        val q = input.optString("query")
        if (q.isBlank()) return ToolOutcome("query kosong.", true)
        val root = tc.root
        val dir = resolve(root, input.optString("path", ""))
        val hits = ArrayList<String>()
        for (f in dir.walkTopDown()) {
            if (hits.size >= 60) break
            if (!f.isFile || f.length() > 500_000 || f.extension.lowercase() !in TEXT_EXT) continue
            f.readLines().forEachIndexed { i, l ->
                if (hits.size < 60 && l.contains(q, ignoreCase = true)) hits.add("${rel(root, f)}:${i + 1}: ${l.trim().take(160)}")
            }
        }
        return ToolOutcome(if (hits.isEmpty()) "Tidak ada hasil untuk \"$q\"." else hits.joinToString("\n"))
    }

    private fun setAppInfo(tc: ToolContext, input: JSONObject): ToolOutcome {
        val id = input.optString("app_id").trim()
        val name = input.optString("app_name").trim()
        if (id.isNotEmpty()) {
            if (!Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(id)) {
                return ToolOutcome("app_id tidak valid. Contoh: com.agenkode.catatan (huruf kecil, angka, titik).", true)
            }
            tc.meta.appId = id
        }
        if (name.isNotEmpty()) {
            tc.meta.appName = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.take(30).ifBlank { "Aplikasi" }
        }
        tc.store.writeMeta(tc.meta)
        return ToolOutcome("Info aplikasi: id=${tc.meta.appId}, nama=${tc.meta.appName}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun checkWebApp(tc: ToolContext): ToolOutcome {
        val root = tc.root
        if (!File(root, "index.html").exists()) return ToolOutcome("index.html belum ada di root proyek.", true)
        tc.progress("Menjalankan aplikasi untuk diuji…")
        val logs: List<String> = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<List<String>> { cont ->
                val out = LinkedHashSet<String>()
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/app/", WebViewAssetLoader.InternalStoragePathHandler(tc.ctx, root))
                    .build()
                val wv = WebView(tc.ctx)
                var done = false
                fun finish() {
                    if (done) return
                    done = true
                    try { wv.stopLoading(); wv.destroy() } catch (e: Exception) { }
                    if (cont.isActive) cont.resume(out.toList())
                }
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        val lvl = m.messageLevel()
                        if (lvl == ConsoleMessage.MessageLevel.ERROR || lvl == ConsoleMessage.MessageLevel.WARNING) {
                            out.add("[$lvl] ${m.message()} (${(m.sourceId() ?: "").substringAfterLast('/')}:${m.lineNumber()})")
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.url.host == "appassets.androidplatform.net") {
                            out.add("HTTP ${errorResponse.statusCode}: ${request.url.path} tidak ditemukan")
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        out.add("Gagal memuat ${request.url}: ${error.description}")
                    }
                }
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { finish() } }
                wv.loadUrl("https://appassets.androidplatform.net/app/index.html")
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 4000)
            }
        }
        return if (logs.isEmpty()) {
            ToolOutcome("OK: index.html dimuat tanpa error console dalam 4 detik. (Interaksi seperti klik tombol tidak ikut diuji; periksa logikanya dengan membaca kode.)")
        } else {
            ToolOutcome("Ditemukan ${logs.size} masalah:\n" + logs.take(20).joinToString("\n") + "\nPerbaiki penyebabnya lalu panggil check_web_app lagi.", true)
        }
    }

    private suspend fun buildApk(tc: ToolContext): ToolOutcome {
        val root = tc.root
        val native = File(root, "settings.gradle.kts").exists() || File(root, "settings.gradle").exists()
        if (!native && !File(root, "index.html").exists()) {
            return ToolOutcome("Tidak ada index.html di root proyek. Buat aplikasinya dulu sebelum build.", true)
        }
        val token = tc.settings.ghToken
        if (token.isBlank()) {
            return ToolOutcome("Token GitHub belum diisi. Minta pengguna membuka Pengaturan > GitHub dan memasukkan token (izin repo + workflow).", true)
        }
        val files = withContext(Dispatchers.IO) {
            val m = LinkedHashMap<String, String>()
            for (f in root.walkTopDown()) {
                if (f.isFile && f.length() < 1_000_000 && f.extension.lowercase() in TEXT_EXT) {
                    m["project/" + rel(root, f)] = f.readText()
                }
            }
            m
        }
        if (files.isEmpty()) return ToolOutcome("Proyek kosong.", true)
        tc.meta.versionCode += 1
        tc.store.writeMeta(tc.meta)

        val builder = GitHubBuilder(tc.ctx, token, tc.settings.ghRepo)
        return when (val r = builder.build(files, tc.meta, tc.progress)) {
            is BuildResult.Success ->
                ToolOutcome("BERHASIL. APK dibangun (versi ${tc.meta.versionCode}).\nAPK_SIAP:${r.file.absolutePath}\nBeritahu pengguna bahwa tombol Install sudah muncul di chat.")
            is BuildResult.Failure -> {
                if (r.counts) tc.buildFailures++
                val tail = if (tc.buildFailures >= 4) "\n\n[SISTEM] Sudah 4 kali gagal. Berhenti, jelaskan masalahnya ke pengguna." else
                    "\n\nPetunjuk: temukan error PERTAMA yang nyata di log, perbaiki akar penyebabnya, lalu build ulang."
                ToolOutcome("BUILD GAGAL (percobaan ke-${tc.buildFailures}).\n${r.log}$tail", true)
            }
        }
    }
}

object Validator {
    /** Peringatan ringan (petunjuk, bukan kebenaran mutlak). Yang otoritatif: check_web_app. */
    fun check(f: File, content: String): String {
        val w = ArrayList<String>()
        val ext = f.extension.lowercase()
        if (ext == "json") {
            try {
                if (content.trimStart().startsWith("[")) JSONArray(content) else JSONObject(content)
            } catch (e: Exception) {
                w.add("JSON tidak valid: ${e.message}")
            }
        }
        if (ext == "html" || ext == "htm") {
            if (!content.contains("viewport")) {
                w.add("Tambahkan <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"> agar tampil benar di HP.")
            }
            val re = Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)["']""")
            for (m in re.findAll(content)) {
                val ref = m.groupValues[1].trim()
                if (ref.startsWith("http") || ref.startsWith("//") || ref.startsWith("data:") ||
                    ref.startsWith("mailto:") || ref.startsWith("tel:") || ref.startsWith("javascript:")
                ) continue
                if (ref.startsWith("/")) {
                    w.add("Path absolut '$ref' gagal di APK; gunakan path relatif.")
                    continue
                }
                if (!File(f.parentFile, ref).exists()) w.add("File yang dirujuk belum ada: $ref (pastikan nanti dibuat).")
            }
        }
        return if (w.isEmpty()) "" else "\nPERINGATAN:\n- " + w.joinToString("\n- ")
    }
}
