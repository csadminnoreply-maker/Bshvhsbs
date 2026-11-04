@file:OptIn(ExperimentalMaterial3Api::class)

package id.agenkode.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import kotlinx.coroutines.launch

// ============================== Tema ==============================
@Composable
fun AgenKodeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF9FA8FF), onPrimary = Color(0xFF14164F),
        primaryContainer = Color(0xFF2F3380), onPrimaryContainer = Color(0xFFE0E0FF),
        secondary = Color(0xFF5FD4C1), error = Color(0xFFFF8A80),
        background = Color(0xFF0F1115), surface = Color(0xFF161A21),
        surfaceVariant = Color(0xFF232832), onSurface = Color(0xFFE6E8EE), onSurfaceVariant = Color(0xFFA9B0BE)
    ) else lightColorScheme(
        primary = Color(0xFF4F46E5), onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E0FF), onPrimaryContainer = Color(0xFF14134A),
        secondary = Color(0xFF0F9D8A),
        background = Color(0xFFF6F7FB), surface = Color.White,
        surfaceVariant = Color(0xFFECEEF6), onSurface = Color(0xFF1B1D24), onSurfaceVariant = Color(0xFF5B6272)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ============================== Navigasi ==============================
@Composable
fun AppRoot(vm: MainVm) {
    BackHandler(enabled = vm.screen !is Screen.Home) {
        vm.screen = when (val s = vm.screen) {
            is Screen.Preview -> Screen.Chat(s.id)
            is Screen.Settings -> s.back
            else -> Screen.Home
        }
        vm.refresh()
    }
    when (val s = vm.screen) {
        is Screen.Home -> HomeScreen(vm)
        is Screen.Chat -> ChatScreen(vm, vm.session(s.id))
        is Screen.Settings -> SettingsScreen(vm, s.back)
        is Screen.Preview -> PreviewScreen(vm, s.id)
    }
}

// ============================== Beranda ==============================
@Composable
fun HomeScreen(vm: MainVm) {
    var showNew by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ProjectMeta?>(null) }
    val hasKey = vm.settings.candidates().isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AgenKode", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.screen = Screen.Settings(Screen.Home) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Proyek baru") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasKey) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Langkah pertama", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Isi API key dari penyedia AI pilihan Anda (Claude, GPT, Gemini, dll.) di Pengaturan.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.screen = Screen.Settings(Screen.Home) }) { Text("Buka Pengaturan") }
                    }
                }
            }
            if (vm.projects.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("</>", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Belum ada proyek", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Buat proyek baru, lalu ceritakan aplikasi yang Anda mau.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 14.sp
                    )
                }
            }
            items(vm.projects, key = { it.id }) { p ->
                Card(onClick = { vm.screen = Screen.Chat(p.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${p.appName} • build ke-${p.versionCode}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { toDelete = p }) { Icon(Icons.Default.Delete, contentDescription = "Hapus") }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("Proyek baru") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nama proyek") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val m = vm.create(name.ifBlank { "Proyek baru" })
                    showNew = false
                    vm.screen = Screen.Chat(m.id)
                }) { Text("Buat") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Batal") } }
        )
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus proyek?") },
            text = { Text("\"${p.name}\" beserta semua file dan riwayat chat akan dihapus.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); toDelete = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } }
        )
    }
}

// ============================== Chat ==============================
@Composable
fun ChatScreen(vm: MainVm, s: ChatSession) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showFiles by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var provMenu by remember { mutableStateOf(false) }
    val count = s.items.size + if (s.running) 1 else 0
    LaunchedEffect(count, s.status) { if (count > 0) listState.animateScrollToItem(count - 1) }
    val provLabel = if (vm.autoMode) "Otomatis" else (PRESETS.firstOrNull { it.id == vm.activeProvider }?.label ?: "AI")

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.meta.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Box {
                            Text(
                                "$provLabel ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { provMenu = true }
                            )
                            DropdownMenu(expanded = provMenu, onDismissRequest = { provMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text((if (vm.autoMode) "✓ " else "") + "Otomatis (disarankan)") },
                                    onClick = { provMenu = false; vm.setAuto(true) }
                                )
                                PRESETS.forEach { p ->
                                    val has = vm.settings.key(p.id).isNotBlank() || p.id == "custom"
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (!vm.autoMode && p.id == vm.activeProvider) "✓ " else "") + p.label +
                                                    (if (!has) "  (belum ada key)" else "")
                                            )
                                        },
                                        onClick = {
                                            provMenu = false
                                            if (has) { vm.setAuto(false); vm.setActive(p.id) } else vm.screen = Screen.Settings(Screen.Chat(s.id))
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Home; vm.refresh() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.screen = Screen.Preview(s.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Pratinjau")
                    }
                    IconButton(
                        onClick = { s.send("Tolong build aplikasi ini menjadi APK sekarang.") },
                        enabled = !s.running
                    ) { Icon(Icons.Default.Build, contentDescription = "Build APK") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Lihat file proyek") }, onClick = { menu = false; showFiles = true })
                            DropdownMenuItem(text = { Text("Bersihkan chat") }, onClick = { menu = false; s.clear() })
                            DropdownMenuItem(
                                text = { Text("Pengaturan") },
                                onClick = { menu = false; vm.screen = Screen.Settings(Screen.Chat(s.id)) }
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.items.isEmpty()) Welcome(onPick = { input = it })
                LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(s.items.size) { i -> ItemView(s.items[i], ctx) }
                    if (s.running) item { StatusRow(s.status) }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Tulis pesan…") }, maxLines = 5, shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    if (s.running) {
                        FilledIconButton(onClick = { s.stop() }) { Icon(Icons.Default.Stop, contentDescription = "Berhenti") }
                    } else {
                        FilledIconButton(
                            onClick = { s.send(input.trim()); input = "" },
                            enabled = input.isNotBlank()
                        ) { Icon(Icons.Default.Send, contentDescription = "Kirim") }
                    }
                }
            }
        }
    }
    if (showFiles) FilesDialog(vm, s.id) { showFiles = false }
}

@Composable
fun Welcome(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("</>", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Mau bikin aplikasi apa hari ini?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ceritakan idenya. AI menulis kode, mengujinya, lalu membangun APK yang bisa langsung dipasang.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "Buat aplikasi catatan harian dengan tema gelap",
            "Buat kalkulator cicilan yang rapi",
            "Buat game tebak angka sederhana",
            "Buat to-do list dengan kategori"
        ).forEach { t ->
            AssistChip(onClick = { onPick(t) }, label = { Text(t, fontSize = 12.sp) }, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
fun StatusRow(text: String) {
    Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text.ifBlank { "Bekerja…" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ItemView(item: UiItem, ctx: Context) {
    val c = MaterialTheme.colorScheme
    when (item) {
        is UiItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = c.primary, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) { Text(item.text, color = c.onPrimary, modifier = Modifier.padding(12.dp), fontSize = 15.sp) }
        }
        is UiItem.Assistant -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Surface(
                color = c.surface, tonalElevation = 1.dp, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                SelectionContainer { Box(Modifier.padding(12.dp)) { RichText(item.text) } }
            }
        }
        is UiItem.Step -> StepCard(item)
        is UiItem.Plan -> PlanCard(item)
        is UiItem.Apk -> ApkCard(item, ctx)
        is UiItem.Info -> Text(
            item.text, color = if (item.isError) c.error else c.onSurfaceVariant, fontSize = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun RichText(text: String) {
    val c = MaterialTheme.colorScheme
    val parts = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val code = part.substringAfter('\n', part).trimEnd()
                Surface(color = c.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        code, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.onSurface,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)
                    )
                }
            } else if (part.isNotBlank()) {
                Text(inlineMd(part.trim(), c.surfaceVariant), color = c.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun inlineMd(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var bold = false
    var code = false
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            val style = SpanStyle(
                fontWeight = if (bold) FontWeight.Bold else null,
                fontFamily = if (code) FontFamily.Monospace else null,
                background = if (code) codeBg else Color.Unspecified
            )
            withStyle(style) { append(sb.toString()) }
            sb.setLength(0)
        }
    }
    var i = 0
    while (i < s.length) {
        if (!code && s.startsWith("**", i)) { flush(); bold = !bold; i += 2 }
        else if (s[i] == '`') { flush(); code = !code; i++ }
        else { sb.append(s[i]); i++ }
    }
    flush()
}

@Composable
fun StepCard(s: UiItem.Step) {
    var open by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp), color = c.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().clickable { open = !open }
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (s.state) {
                    0 -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    1 -> Icon(Icons.Default.Check, contentDescription = null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    else -> Icon(Icons.Default.Close, contentDescription = null, tint = c.error, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    s.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            if (open && s.result.isNotBlank()) {
                Text(
                    s.result.take(1500), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            } else if (s.state == 2 && s.result.isNotBlank()) {
                Text(
                    s.result.lineSequence().first().take(140), color = c.error, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun PlanCard(p: UiItem.Plan) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.primaryContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Rencana kerja", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            p.steps.forEach { st ->
                val mark = when (st.status) { "done" -> "✓"; "in_progress" -> "▶"; else -> "○" }
                Text(
                    "$mark  ${st.text}", fontSize = 13.sp,
                    fontWeight = if (st.status == "in_progress") FontWeight.SemiBold else FontWeight.Normal,
                    color = if (st.status == "done") c.onSurfaceVariant else c.onSurface
                )
            }
        }
    }
}

fun installApk(ctx: Context, file: File) {
    if (!file.exists()) { Toast.makeText(ctx, "File APK tidak ditemukan.", Toast.LENGTH_SHORT).show(); return }
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(ctx, "Izinkan AgenKode memasang aplikasi, lalu tekan Install lagi.", Toast.LENGTH_LONG).show()
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun ApkCard(a: UiItem.Apk, ctx: Context) {
    val f = File(a.path)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("APK siap dipasang", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "${f.name} • ${f.length() / 1024} KB", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Button(onClick = { installApk(ctx, f) }) { Text("Install") }
        }
    }
}

@Composable
fun FilesDialog(vm: MainVm, id: String, onDismiss: () -> Unit) {
    val root = remember { vm.store.files(id) }
    val files = remember { root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() }
    var open by remember { mutableStateOf<File?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(open?.name ?: "File proyek") },
        text = {
            val f = open
            if (f != null) {
                Text(
                    f.readText().take(20000), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                )
            } else if (files.isEmpty()) {
                Text("Belum ada file.")
            } else {
                LazyColumn {
                    items(files) { file ->
                        Text(
                            file.relativeTo(root).path + "  (${file.length()} B)", fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable { open = file }.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (open != null) open = null else onDismiss() }) { Text(if (open != null) "Kembali" else "Tutup") } }
    )
}

// ============================== Pratinjau ==============================
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: MainVm, id: String) {
    val dir = remember { vm.store.files(id).canonicalFile }
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pratinjau") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Chat(id) }) { Icon(Icons.Default.ArrowBack, contentDescription = "Kembali") }
                },
                actions = {
                    IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Muat ulang") }
                }
            )
        }
    ) { pad ->
        if (!File(dir, "index.html").exists()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Belum ada index.html.\nMinta AI membuat aplikasinya dulu.", textAlign = TextAlign.Center)
            }
        } else {
            AndroidView(
                modifier = Modifier.padding(pad).fillMaxSize(),
                factory = { c ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/app/", WebViewAssetLoader.InternalStoragePathHandler(c, dir))
                        .build()
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)
                        }
                        loadUrl("https://appassets.androidplatform.net/app/index.html")
                        web = this
                    }
                }
            )
        }
    }
}

// ============================== Pengaturan ==============================
@Composable
fun SettingsScreen(vm: MainVm, back: Screen) {
    val st = vm.settings
    var gh by remember { mutableStateOf(st.ghToken) }
    var repo by remember { mutableStateOf(st.ghRepo) }
    var active by remember { mutableStateOf(st.active) }
    var auto by remember { mutableStateOf(st.auto) }
    var paste by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val results = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val keys = remember { mutableStateMapOf<String, String>().apply { PRESETS.forEach { put(it.id, st.key(it.id)) } } }
    val models = remember { mutableStateMapOf<String, String>().apply { PRESETS.forEach { put(it.id, st.model(it)) } } }
    val urls = remember { mutableStateMapOf<String, String>().apply { PRESETS.forEach { put(it.id, st.baseUrl(it)) } } }

    fun save() {
        PRESETS.forEach { p ->
            st.setKey(p.id, keys[p.id] ?: "")
            st.setModel(p.id, models[p.id] ?: p.model)
            st.setBaseUrl(p.id, urls[p.id] ?: p.baseUrl)
        }
        st.active = active
        st.auto = auto
        vm.setAuto(auto)
        Router.reset()
        st.ghToken = gh.trim()
        st.ghRepo = repo.trim().ifBlank { "agenkode-builds" }
        vm.setActive(active)
        vm.screen = back
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = back }) { Icon(Icons.Default.ArrowBack, contentDescription = "Kembali") }
                },
                actions = { TextButton(onClick = { save() }) { Text("Simpan") } }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("Penyedia AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "Isi key untuk provider yang ingin dipakai, lalu pilih satu sebagai aktif. Key disimpan terenkripsi di HP ini saja.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mode Otomatis", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Memilih AI terbaik dari key yang ada, dan pindah sendiri ke cadangan jika error atau kena batas.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
            }
            item {
                OutlinedTextField(
                    value = paste, onValueChange = { paste = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tempel banyak key (satu per baris)") }, minLines = 2, maxLines = 5,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val done = st.importKeys(paste, true)
                        PRESETS.forEach { keys[it.id] = st.key(it.id) }
                        paste = ""
                        Toast.makeText(ctx, if (done.isEmpty()) "Tidak ada key yang dikenali" else "Terimpor: " + done.joinToString(", "), Toast.LENGTH_LONG).show()
                    }, enabled = paste.isNotBlank()) { Text("Impor key") }
                    OutlinedButton(onClick = {
                        testing = true
                        results.clear()
                        scope.launch {
                            for (p in PRESETS) {
                                val k = (keys[p.id] ?: "").trim()
                                if (k.isEmpty()) continue
                                results[p.id] = "menguji…"
                                val cfg = ProviderConfig(p.id, p.kind, (urls[p.id] ?: p.baseUrl).trim(), k, (models[p.id] ?: p.model).trim(), p.label)
                                results[p.id] = Router.test(cfg)
                            }
                            testing = false
                        }
                    }, enabled = !testing) { Text(if (testing) "Menguji…" else "Tes semua key") }
                }
            }
            items(PRESETS) { p ->
                var open by remember { mutableStateOf(p.id == active) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = active == p.id, onClick = { active = p.id })
                            Text(p.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if ((keys[p.id] ?: "").isNotBlank()) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        results[p.id]?.let { r ->
                            Text(
                                if (r == "OK") "✓ Berfungsi" else r, fontSize = 12.sp,
                                color = if (r == "OK") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                            )
                        }
                        if (open) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = keys[p.id] ?: "", onValueChange = { keys[p.id] = it },
                                label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = models[p.id] ?: "", onValueChange = { models[p.id] = it },
                                label = { Text("Nama model") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = urls[p.id] ?: "", onValueChange = { urls[p.id] = it },
                                label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { Text("GitHub (untuk membangun APK)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "APK dibangun gratis oleh GitHub Actions. Buat token di github.com/settings/tokens → Generate new token (classic) → centang \"repo\" dan \"workflow\". AgenKode otomatis membuat repo PRIVAT milik Anda dan mengunggah kode proyek ke sana.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = gh, onValueChange = { gh = it }, label = { Text("GitHub token") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation()
                )
            }
            item {
                OutlinedTextField(
                    value = repo, onValueChange = { repo = it }, label = { Text("Nama repo build") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Simpan") } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
