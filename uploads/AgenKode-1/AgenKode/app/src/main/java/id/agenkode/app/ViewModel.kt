package id.agenkode.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

sealed class Screen {
    object Home : Screen()
    data class Chat(val id: String) : Screen()
    data class Preview(val id: String) : Screen()
    data class Settings(val back: Screen) : Screen()
}

sealed class UiItem {
    data class User(val text: String) : UiItem()
    data class Assistant(val text: String) : UiItem()
    /** state: 0 berjalan, 1 sukses, 2 gagal */
    data class Step(val id: String, val name: String, val title: String, val result: String = "", val state: Int = 0) : UiItem()
    data class Plan(val steps: List<PlanItem>) : UiItem()
    data class Apk(val path: String) : UiItem()
    data class Info(val text: String, val isError: Boolean = false) : UiItem()
}

fun stepTitle(name: String, input: JSONObject): String {
    val p = input.optString("path")
    return when (name) {
        "list_files" -> "Melihat daftar file"
        "read_file" -> "Membaca $p"
        "write_file" -> "Menulis $p"
        "edit_file" -> "Mengedit $p"
        "delete_file" -> "Menghapus $p"
        "search_files" -> "Mencari \"${input.optString("query")}\""
        "check_web_app" -> "Menguji aplikasi (mencari error)"
        "set_app_info" -> "Mengatur info aplikasi"
        "build_apk" -> "Membangun APK di cloud"
        else -> name
    }
}

fun applyPlan(list: MutableList<UiItem>, steps: List<PlanItem>) {
    val i = list.indexOfLast { it is UiItem.Plan }
    if (i >= 0) list[i] = UiItem.Plan(steps) else list.add(UiItem.Plan(steps))
}

private val APK_RE = Regex("APK_SIAP:(.+)")

fun rebuildItems(h: List<Msg>): List<UiItem> {
    val out = ArrayList<UiItem>()
    val idx = HashMap<String, Int>()
    for (m in h) {
        for (b in m.blocks) {
            when (b) {
                is Block.Text -> if (b.text.isNotBlank()) {
                    out.add(if (m.role == "user") UiItem.User(b.text) else UiItem.Assistant(b.text))
                }
                is Block.ToolUse -> if (b.name == "update_plan") {
                    applyPlan(out, parsePlan(b.input))
                } else {
                    idx[b.id] = out.size
                    out.add(UiItem.Step(b.id, b.name, stepTitle(b.name, b.input)))
                }
                is Block.ToolResult -> {
                    val i = idx[b.toolUseId]
                    if (i != null) {
                        val s = out[i] as UiItem.Step
                        out[i] = s.copy(result = b.content, state = if (b.isError) 2 else 1)
                        if (s.name == "build_apk") {
                            val path = APK_RE.find(b.content)?.groupValues?.get(1)?.trim()
                            if (path != null && File(path).exists()) out.add(UiItem.Apk(path))
                        }
                    }
                }
            }
        }
    }
    return out
}

class ChatSession(private val vm: MainVm, val id: String) {
    val items = mutableStateListOf<UiItem>()
    val history = mutableListOf<Msg>()
    var running by mutableStateOf(false)
    var status by mutableStateOf("")
    var meta: ProjectMeta = vm.store.readMeta(id) ?: ProjectMeta(id, "Proyek", "com.agenkode.app", "Aplikasi", 0, 0L)
    private var job: Job? = null

    fun load() {
        history.clear()
        history.addAll(vm.store.loadChat(id))
        sanitizeHistory(history)
        items.clear()
        items.addAll(rebuildItems(history))
    }

    fun stop() { job?.cancel() }

    fun clear() {
        if (running) return
        history.clear()
        items.clear()
        vm.store.saveChat(id, history)
    }

    fun send(text: String) {
        if (running || text.isBlank()) return
        running = true
        items.add(UiItem.User(text))
        job = vm.viewModelScope.launch {
            try {
                val cands = vm.settings.candidates()
                if (cands.isEmpty()) {
                    throw LlmException("Belum ada API key. Buka Pengaturan (ikon gerigi) untuk mengisinya.")
                }
                val llm: Llm = FallbackLlm(cands) { name -> items.add(UiItem.Info("Beralih ke $name…")) }
                val tc = ToolContext(
                    vm.getApplication<Application>(), vm.store, vm.settings, meta,
                    { status = it },
                    { plan -> applyPlan(items, plan) }
                )
                Agent.run(llm, history, text, tc) { ev -> onEvent(ev) }
            } catch (e: CancellationException) {
                items.add(UiItem.Info("Dihentikan."))
            } catch (e: Exception) {
                items.add(UiItem.Info(e.message ?: e.toString(), true))
            } finally {
                running = false
                status = ""
                withContext(NonCancellable + Dispatchers.IO) {
                    meta.updated = System.currentTimeMillis()
                    vm.store.saveChat(id, history)
                    vm.store.writeMeta(meta)
                }
                vm.refresh()
            }
        }
    }

    private fun onEvent(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Text -> items.add(UiItem.Assistant(ev.text))
            is AgentEvent.Status -> status = ev.text
            is AgentEvent.ToolStart -> {
                if (ev.name == "update_plan") {
                    applyPlan(items, parsePlan(ev.input))
                } else {
                    val title = stepTitle(ev.name, ev.input)
                    items.add(UiItem.Step(ev.id, ev.name, title))
                    status = "$title…"
                }
            }
            is AgentEvent.ToolEnd -> {
                val i = items.indexOfLast { it is UiItem.Step && it.id == ev.id }
                if (i >= 0) {
                    val s = items[i] as UiItem.Step
                    items[i] = s.copy(result = ev.result, state = if (ev.isError) 2 else 1)
                }
                if (ev.name == "build_apk") {
                    val path = APK_RE.find(ev.result)?.groupValues?.get(1)?.trim()
                    if (path != null) items.add(UiItem.Apk(path))
                }
            }
        }
    }
}

class MainVm(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    val store = ProjectStore(app)
    val projects = mutableStateListOf<ProjectMeta>()
    var activeProvider by mutableStateOf(settings.active)
    var autoMode by mutableStateOf(settings.auto)
    var screen by mutableStateOf<Screen>(Screen.Home)
    private val sessions = HashMap<String, ChatSession>()

    init {
        // Key pengembangan (opsional): hanya mengisi provider yang belum punya key.
        try {
            val t = app.assets.open("devkeys.txt").bufferedReader().use { it.readText() }
            settings.importKeys(t, false)
        } catch (e: Exception) { }
        refresh()
    }

    fun refresh() {
        projects.clear()
        projects.addAll(store.list())
    }

    fun create(name: String): ProjectMeta {
        val m = store.create(name)
        refresh()
        return m
    }

    fun delete(id: String) {
        sessions.remove(id)?.stop()
        store.delete(id)
        refresh()
    }

    fun session(id: String): ChatSession = sessions.getOrPut(id) { ChatSession(this, id).also { it.load() } }

    fun setAuto(b: Boolean) {
        settings.auto = b
        autoMode = b
    }

    fun setActive(id: String) {
        settings.active = id
        activeProvider = id
    }
}
