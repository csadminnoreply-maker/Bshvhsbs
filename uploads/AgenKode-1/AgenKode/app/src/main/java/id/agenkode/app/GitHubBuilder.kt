package id.agenkode.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

sealed class BuildResult {
    data class Success(val file: File) : BuildResult()
    /** counts=false untuk masalah non-kode (token, jaringan) agar tidak dihitung sebagai kegagalan build. */
    data class Failure(val log: String, val counts: Boolean = true) : BuildResult()
}

/**
 * Membangun APK tanpa server sendiri: kode proyek diunggah ke repo GitHub PRIBADI milik pengguna,
 * GitHub Actions membangunnya, lalu APK diunduh kembali ke HP.
 */
class GitHubBuilder(private val ctx: Context, private val token: String, repoName: String) {
    private val api = "https://api.github.com"
    private val repo = repoName.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.ifBlank { "agenkode-builds" }

    private class GhError(val code: Int, message: String) : Exception(message)

    private suspend fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val rb = Request.Builder().url(if (path.startsWith("http")) path else api + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AgenKode")
        when (method) {
            "POST" -> rb.post(Net.jsonBody(body ?: JSONObject()))
            "PATCH" -> rb.patch(Net.jsonBody(body ?: JSONObject()))
            else -> rb.get()
        }
        return Net.send(rb.build())
    }

    private suspend fun ok(method: String, path: String, body: JSONObject? = null): JSONObject {
        val (c, t) = call(method, path, body)
        if (c !in 200..299) throw GhError(c, ghMsg(c, t))
        return if (t.isBlank()) JSONObject() else JSONObject(t)
    }

    private fun ghMsg(code: Int, body: String): String {
        val m = try { JSONObject(body).optString("message") } catch (e: Exception) { body.take(200) }
        return when (code) {
            401 -> "Token GitHub salah atau kedaluwarsa (401). Perbarui di Pengaturan."
            403 -> "Akses ditolak GitHub (403): $m"
            404 -> "Tidak ditemukan (404): $m. Pastikan token punya izin 'repo' dan 'workflow'."
            422 -> "GitHub menolak permintaan (422): $m"
            else -> "GitHub error $code: $m"
        }
    }

    suspend fun build(files: Map<String, String>, meta: ProjectMeta, progress: (String) -> Unit): BuildResult {
        try {
            progress("Memeriksa akun GitHub…")
            val owner = ok("GET", "/user").getString("login")
            val branch = ensureRepo(owner)

            progress("Mengunggah ${files.size} file ke GitHub…")
            push(owner, branch, files + template(), meta)

            progress("Memulai build di GitHub…")
            val before = latestRunId(owner)
            dispatch(owner, branch, meta)
            val runId = findRun(owner, before)

            val started = System.currentTimeMillis()
            while (true) {
                delay(7000)
                val run = ok("GET", "/repos/$owner/$repo/actions/runs/$runId")
                val status = run.getString("status")
                val secs = (System.currentTimeMillis() - started) / 1000
                progress("Membangun di GitHub… ${secs} dtk (${if (status == "queued") "antre" else "berjalan"})")
                if (status == "completed") {
                    return if (run.optString("conclusion") == "success") downloadApk(owner, runId, meta, progress)
                    else failureLog(owner, runId, run.optString("conclusion"))
                }
                if (secs > 1200) return BuildResult.Failure("Build melewati 20 menit. Periksa tab Actions di repo GitHub.", false)
            }
        } catch (e: GhError) {
            return BuildResult.Failure(e.message ?: "Error GitHub", false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return BuildResult.Failure("Koneksi ke GitHub gagal: ${e.message}", false)
        } catch (e: Exception) {
            return BuildResult.Failure("Error tak terduga: ${e.message}", false)
        }
    }

    private fun template(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val list = listOf(
            "shell/settings.gradle.kts", "shell/build.gradle.kts", "shell/gradle.properties",
            "shell/app/build.gradle.kts", "shell/app/src/main/AndroidManifest.xml",
            "shell/app/src/main/java/app/shell/MainActivity.java"
        )
        for (p in list) m[p] = ctx.assets.open(p).bufferedReader().use { it.readText() }
        m[".github/workflows/build.yml"] = ctx.assets.open("workflow/build.yml").bufferedReader().use { it.readText() }
        return m
    }

    private suspend fun ensureRepo(owner: String): String {
        val (c, t) = call("GET", "/repos/$owner/$repo")
        if (c == 200) return JSONObject(t).optString("default_branch", "main")
        if (c != 404) throw GhError(c, ghMsg(c, t))
        val r = ok(
            "POST", "/user/repos",
            JSONObject().put("name", repo).put("private", true).put("auto_init", true)
                .put("description", "Build APK oleh AgenKode")
        )
        delay(2500)
        return r.optString("default_branch", "main")
    }

    private suspend fun push(owner: String, branch: String, files: Map<String, String>, meta: ProjectMeta) {
        var refJson: JSONObject? = null
        for (i in 0 until 6) {
            val (c, t) = call("GET", "/repos/$owner/$repo/git/ref/heads/$branch")
            if (c == 200) { refJson = JSONObject(t); break }
            delay(2000)
        }
        val head = (refJson ?: throw GhError(404, "Branch $branch belum siap. Coba lagi sebentar."))
            .getJSONObject("object").getString("sha")
        val baseTree = ok("GET", "/repos/$owner/$repo/git/commits/$head").getJSONObject("tree").getString("sha")
        val existing = ok("GET", "/repos/$owner/$repo/git/trees/$baseTree?recursive=1").getJSONArray("tree")

        val entries = JSONArray()
        for ((p, c) in files) {
            entries.put(
                JSONObject().put("path", p).put("mode", "100644").put("type", "blob").put("content", c.ifEmpty { "\n" })
            )
        }
        for (i in 0 until existing.length()) {
            val e = existing.getJSONObject(i)
            val p = e.getString("path")
            if (e.getString("type") == "blob" && p.startsWith("project/") && p !in files) {
                entries.put(JSONObject().put("path", p).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
            }
        }
        val newTree = ok("POST", "/repos/$owner/$repo/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
            .getString("sha")
        val commit = ok(
            "POST", "/repos/$owner/$repo/git/commits",
            JSONObject().put("message", "Build ${meta.appName} v${meta.versionCode}").put("tree", newTree)
                .put("parents", JSONArray().put(head))
        ).getString("sha")
        ok("PATCH", "/repos/$owner/$repo/git/refs/heads/$branch", JSONObject().put("sha", commit))
    }

    private suspend fun latestRunId(owner: String): Long {
        val (c, t) = call("GET", "/repos/$owner/$repo/actions/workflows/build.yml/runs?per_page=1")
        if (c != 200) return 0L
        val arr = JSONObject(t).optJSONArray("workflow_runs") ?: return 0L
        return if (arr.length() > 0) arr.getJSONObject(0).getLong("id") else 0L
    }

    private suspend fun dispatch(owner: String, branch: String, meta: ProjectMeta) {
        val inputs = JSONObject().put("app_id", meta.appId).put("app_name", meta.appName)
            .put("version_code", meta.versionCode.toString())
        val body = JSONObject().put("ref", branch).put("inputs", inputs)
        var last = ""
        for (i in 0 until 8) {
            val (c, t) = call("POST", "/repos/$owner/$repo/actions/workflows/build.yml/dispatches", body)
            if (c == 204) return
            last = ghMsg(c, t)
            if (c != 404 && c != 422) throw GhError(c, last)
            delay(4000) // workflow yang baru diunggah perlu beberapa detik untuk dikenali GitHub
        }
        throw GhError(422, last)
    }

    private suspend fun findRun(owner: String, before: Long): Long {
        for (i in 0 until 15) {
            val o = ok("GET", "/repos/$owner/$repo/actions/workflows/build.yml/runs?event=workflow_dispatch&per_page=5")
            val arr = o.getJSONArray("workflow_runs")
            for (j in 0 until arr.length()) {
                val id = arr.getJSONObject(j).getLong("id")
                if (id > before) return id
            }
            delay(4000)
        }
        throw GhError(0, "Run build tidak ditemukan. Periksa tab Actions di repo GitHub.")
    }

    private suspend fun downloadApk(owner: String, runId: Long, meta: ProjectMeta, progress: (String) -> Unit): BuildResult {
        val arts = ok("GET", "/repos/$owner/$repo/actions/runs/$runId/artifacts").getJSONArray("artifacts")
        if (arts.length() == 0) return BuildResult.Failure("Build sukses tetapi artifact APK tidak ditemukan.", false)
        val artId = arts.getJSONObject(0).getLong("id")
        progress("Mengunduh APK…")
        val dir = File(ctx.filesDir, "apks").apply { mkdirs() }
        val out = File(dir, "${meta.id}-v${meta.versionCode}.apk")
        val req = Request.Builder().url("$api/repos/$owner/$repo/actions/artifacts/$artId/zip")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AgenKode").build()
        withContext(Dispatchers.IO) {
            Net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw GhError(resp.code, "Gagal mengunduh artifact (${resp.code})")
                val stream = resp.body?.byteStream() ?: throw GhError(0, "Respons kosong")
                ZipInputStream(stream).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (e.name.endsWith(".apk")) {
                            out.outputStream().use { zin.copyTo(it) }
                            break
                        }
                        e = zin.nextEntry
                    }
                }
            }
        }
        return if (out.exists() && out.length() > 0) BuildResult.Success(out)
        else BuildResult.Failure("APK tidak ditemukan di dalam artifact.", false)
    }

    private suspend fun failureLog(owner: String, runId: Long, conclusion: String): BuildResult {
        val jobs = ok("GET", "/repos/$owner/$repo/actions/runs/$runId/jobs").getJSONArray("jobs")
        var log = ""
        var stepName = ""
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            if (j.optString("conclusion") == "failure") {
                val steps = j.optJSONArray("steps")
                if (steps != null) {
                    for (k in 0 until steps.length()) {
                        val s = steps.getJSONObject(k)
                        if (s.optString("conclusion") == "failure") { stepName = s.optString("name"); break }
                    }
                }
                val (c, t) = call("GET", "/repos/$owner/$repo/actions/jobs/${j.getLong("id")}/logs")
                if (c == 200) log = t
                break
            }
        }
        if (log.isBlank()) return BuildResult.Failure("Build berakhir dengan status: $conclusion (log tidak tersedia).", true)
        return BuildResult.Failure("Langkah gagal: $stepName\n" + summarize(log), true)
    }

    private fun summarize(raw: String): String {
        val ts = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")
        val lines = raw.lines().map { it.replace(ts, "") }
        val key = Regex("(?i)(error|FAILURE|What went wrong|Could not|Execution failed|e: |unresolved|not found|Caused by)")
        val errs = lines.filter { key.containsMatchIn(it) }.distinct().take(40).joinToString("\n").take(3500)
        val tail = lines.takeLast(40).joinToString("\n").takeLast(2500)
        return "=== Baris penting ===\n$errs\n=== Ekor log ===\n$tail"
    }
}
