package com.houseledger.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import androidx.core.view.WindowCompat
import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import com.houseledger.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val container = (application as LedgerApplication).container
            val vm: LedgerViewModel = viewModel(factory = LedgerViewModel.factory(container))
            val preference by vm.theme.collectAsStateWithLifecycle()
            val dark = preference == "DARK" || (preference == "SYSTEM" && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8DD6BE), secondary = Color(0xFFB4CBC1)) else lightColorScheme(primary = Color(0xFF126753), secondary = Color(0xFF52685E), primaryContainer = Color(0xFFD0F0E1), surface = Color(0xFFF8FAF7))) {
                LedgerApp(vm, preference)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LedgerApp(vm: LedgerViewModel, theme: String) {
    val s by vm.ledger.collectAsStateWithLifecycle(); val busy by vm.busy.collectAsStateWithLifecycle(); val error by vm.error.collectAsStateWithLifecycle()
    val nav = rememberNavController(); val entry by nav.currentBackStackEntryAsState(); val route = entry?.destination?.route.orEmpty()
    val tabs = listOf("home", "personal", "household", "reports")
    val titles = listOf(R.string.home, R.string.personal, R.string.household, R.string.reports)
    val icons = listOf(Icons.Default.Home, Icons.Default.AccountBalanceWallet, Icons.Default.Groups, Icons.Default.BarChart)
    val context = LocalContext.current; val messages = remember { Messages(context) }; val snack = remember { SnackbarHostState() }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }; val month = YearMonth.parse(monthText)
    var add by remember { mutableStateOf(false) }; var preview by rememberSaveable { mutableStateOf<String?>(null) }; var previewNote by rememberSaveable { mutableStateOf("") }; var includeNote by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val openShare: (String, String) -> Unit = { text, note -> preview = text; previewNote = note; includeNote = false; copied = false }
    var exportContent by rememberSaveable { mutableStateOf("") }; var restore by remember { mutableStateOf<Backup?>(null) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.run { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(exportContent) } ?: throw LedgerException(Problem.BACKUP) }; snack.showSnackbar(context.getString(R.string.file_saved)) }
    }
    val csvFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.run { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(exportContent) } ?: throw LedgerException(Problem.BACKUP) }; snack.showSnackbar(context.getString(R.string.file_saved)) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.run { val parsed = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; ensure(output.size() + count <= 20_000_000, Problem.BACKUP); output.write(buffer, 0, count) }; output.toByteArray()
            } ?: throw LedgerException(Problem.BACKUP)
            ensure(bytes.size <= 20_000_000, Problem.BACKUP); Backups.decode(bytes.toString(Charsets.UTF_8))
        }; restore = parsed }
    }
    val go: (String) -> Unit = { nav.navigate(it) }; val back: () -> Unit = { nav.popBackStack(); Unit }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (route in tabs) label(titles[tabs.indexOf(route)]) else label(R.string.app_name)) }, navigationIcon = {
            if (route !in tabs && s?.household != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, label(R.string.back)) }
        }, actions = { if (s?.household != null && route in tabs) IconButton(onClick = { go("settings") }) { Icon(Icons.Default.Settings, label(R.string.settings)) } }) },
        bottomBar = { if (s?.household != null && route in tabs) NavigationBar { tabs.forEachIndexed { i, tab -> NavigationBarItem(selected = route == tab, onClick = { nav.navigate(tab) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icons[i], null) }, label = { Text(label(titles[i])) }) } } },
        floatingActionButton = { if (s?.household != null && route in tabs) ExtendedFloatingActionButton(onClick = { add = true }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.add) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(label(R.string.add)) }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val state = s
            when {
                state == null -> Text(label(R.string.loading), Modifier.padding(24.dp))
                state.household == null -> Setup(vm, busy)
                else -> NavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(state, month, { monthText = it.toString() }, go) }
                    composable("personal") { PersonalScreen(state, month, { monthText = it.toString() }, go) }
                    composable("household") { HouseholdScreen(state, month, { monthText = it.toString() }, go) }
                    composable("reports") { ReportsScreen(state, month, { monthText = it.toString() }, go, { openShare(messages.month(state, month), "") }, { exportContent = messages.csv(state, month); csvFile.launch("house-report-$month.csv") }) }
                    composable("settings") { SettingsScreen(state, vm, go, theme, {
                        vm.run { exportContent = Backups.encode(Backup(theme = theme, ledger = vm.container.repository.snapshot())); file.launch("house-ledger-backup.json") }
                    }, { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) }
                    composable("profile") { ProfileForm(state, vm, busy, back) }
                    composable("manage/{type}/{id}") { b -> ManageForm(state, vm, b.arguments!!.getString("type")!!, b.arguments!!.getString("id")!!, busy, back) }
                    composable("form/personal/{kind}") { b -> PersonalForm(state, vm, b.arguments!!.getString("kind")!!, null, back, busy) }
                    composable("form/expense") { ExpenseForm(state, vm, null, back, busy) }
                    composable("form/transfer/{kind}") { b -> TransferForm(state, vm, b.arguments!!.getString("kind")!!, null, back, busy) }
                    composable("edit/{type}/{id}") { b -> when (b.arguments!!.getString("type")) {
                        "personal" -> PersonalForm(state, vm, Kinds.EXPENSE, b.arguments!!.getString("id"), back, busy)
                        "expense" -> ExpenseForm(state, vm, b.arguments!!.getString("id"), back, busy)
                        else -> TransferForm(state, vm, Kinds.OTHER, b.arguments!!.getString("id"), back, busy)
                    } }
                    composable("detail/{type}/{id}") { b -> DetailScreen(state, b.arguments!!.getString("type")!!, b.arguments!!.getString("id")!!, vm, go, back, openShare, messages) }
                    composable("member/{id}") { b -> MemberScreen(state, b.arguments!!.getString("id")!!, go, openShare, messages) }
                }
            }
        }
    }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text(label(R.string.add)) }, text = {
        Column { listOf(R.string.income to "form/personal/INCOME", R.string.expense to "form/personal/EXPENSE", R.string.shared_expense to "form/expense", R.string.settlement to "form/transfer/BILL_SETTLEMENT", R.string.other_money to "form/transfer/OTHER").forEach { (name, destination) -> TextButton(onClick = { add = false; go(destination) }) { Text(label(name)) } } }
    }, confirmButton = { TextButton(onClick = { add = false }) { Text(label(R.string.close)) } })
    if (error != null) AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text(label(R.string.error_title)) }, text = { Text(if (error!!.problem == Problem.LINKED) label(R.string.error_linked, error!!.detail) else label(problemResource(error!!.problem))) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text(label(R.string.close)) } })
    restore?.let { backup -> AlertDialog(onDismissRequest = { restore = null }, title = { Text(label(R.string.restore_title)) }, text = {
        val data = backup.ledger
        Text(label(R.string.restore_summary, data.household!!.name, data.members.size, data.personal.size, data.expenses.size, data.transfers.size, data.allocations.size))
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        vm.run({ restore = null; nav.navigate("home") { popUpTo("home") { inclusive = true } } }) { vm.container.repository.restore(backup); vm.container.preferences.theme(backup.theme); snack.showSnackbar(context.getString(R.string.restored)) }
    }) { Text(label(R.string.replace)) } }, dismissButton = { TextButton(onClick = { restore = null }) { Text(label(R.string.cancel)) } }) }
    if (preview != null) Dialog(onDismissRequest = { preview = null }) {
        Surface(shape = MaterialTheme.shapes.large) { Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(label(R.string.share_preview))
            val text = preview!! + if (includeNote && previewNote.isNotBlank()) "\n" + label(R.string.note_line, previewNote) else ""
            Text(text, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            if (previewNote.isNotBlank()) { Hint(previewNote); LabeledCheck(includeNote, label(R.string.include_note)) { includeNote = it; copied = false } }
            Hint(label(R.string.handoff))
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser)))
            }, modifier = Modifier.fillMaxWidth()) { Text(label(R.string.share_whatsapp)) }
            OutlinedButton(onClick = { (context.getSystemService(CLIPBOARD_SERVICE_NAME) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.share_preview), text)); copied = true }, modifier = Modifier.fillMaxWidth()) { Text(label(R.string.copy)) }
            if (copied) Hint(label(R.string.copied))
            TextButton(onClick = { preview = null }) { Text(label(R.string.close)) }
        } }
    }
}
private const val CLIPBOARD_SERVICE_NAME = "clipboard"
