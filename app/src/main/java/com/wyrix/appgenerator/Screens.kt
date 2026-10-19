package com.wyrix.appgenerator

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WyrixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
fun Root(vm: AppViewModel, onShare: (Uri) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wyrix App Generator AI", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }
        Spacer(Modifier.height(8.dp))
        when (s.stage) {
            Stage.IDLE -> IdleScreen(s, vm)
            Stage.PLANNING -> BusyScreen("Plan ban raha hai…", s, onStop = vm::cancel)
            Stage.PLAN_READY -> PlanScreen(s, vm)
            Stage.GENERATING, Stage.REVIEWING -> GeneratingScreen(s, vm)
            Stage.DONE -> DoneScreen(s, vm, onShare)
            Stage.ERROR -> ErrorScreen(s, vm)
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable
private fun IdleScreen(s: UiState, vm: AppViewModel) {
    var text by rememberSaveable { mutableStateOf(s.prompt) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Kya banana hai? Ek prompt mein likhein.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Jaise: stock market jaisi website jisme login, live charts, portfolio aur admin panel ho") },
        )
        Button(
            onClick = { vm.startPlan(text.trim()) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Plan banayein") }
        if (s.hasSaved) {
            OutlinedButton(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) {
                Text("Pichla project jaari rakhein")
            }
        }
    }
}

@Composable
private fun BusyScreen(title: String, s: UiState, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(title)
        LogBox(s.log, Modifier.weight(1f).fillMaxWidth())
        OutlinedButton(onClick = onStop) { Text("Rokein") }
    }
}

@Composable
private fun PlanScreen(s: UiState, vm: AppViewModel) {
    val plan = s.plan ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(plan.name, style = MaterialTheme.typography.titleLarge)
        Text("Type: ${plan.type}  •  ${plan.files.size} files", style = MaterialTheme.typography.labelLarge)
        Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
        if (s.files.isNotEmpty()) {
            Text("${s.files.size}/${plan.files.size} files pehle se bani hain")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(plan.files) { f ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(f.path, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(f.purpose.take(110), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(onClick = vm::startGenerate, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.files.isEmpty()) "Shuru karein" else "Jaari rakhein")
        }
        OutlinedButton(onClick = vm::backToPrompt, modifier = Modifier.fillMaxWidth()) {
            Text("Prompt badlein")
        }
    }
}

@Composable
private fun GeneratingScreen(s: UiState, vm: AppViewModel) {
    val p = if (s.total == 0) 0f else s.done.toFloat() / s.total
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (s.stage == Stage.REVIEWING) "Review chal raha hai…" else "Code likh raha hai…",
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        Text("${s.done}/${s.total} files")
        Text(s.current, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        LogBox(s.log, Modifier.weight(1f).fillMaxWidth())
        OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("Rokein") }
    }
}

@Composable
private fun DoneScreen(s: UiState, vm: AppViewModel, onShare: (Uri) -> Unit) {
    var preview by remember { mutableStateOf<String?>(null) }
    val lines = s.files.values.sumOf { c -> c.count { it == '\n' } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Project taiyar!", style = MaterialTheme.typography.titleLarge)
        Text("${s.files.size} files, lagbhag $lines lines")
        Text("File par tap karke dekhein.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(s.files.keys.toList()) { path ->
                Text(
                    path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { preview = path }.padding(vertical = 6.dp),
                )
            }
        }
        if (s.message.isNotBlank()) Text(s.message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = vm::exportZip, modifier = Modifier.fillMaxWidth()) { Text("ZIP export karein") }
        s.exportUri?.let { uri ->
            OutlinedButton(onClick = { onShare(uri) }, modifier = Modifier.fillMaxWidth()) { Text("ZIP share karein") }
        }
        TextButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Naya project") }
    }
    preview?.let { path ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Band") } },
            title = { Text(path, fontSize = 14.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        (s.files[path] ?: "").take(6000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            },
        )
    }
}

@Composable
private fun ErrorScreen(s: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Kuch galat hua", style = MaterialTheme.typography.titleLarge)
        Text(s.message)
        if (s.plan != null) {
            Text("Ab tak ki files save hain, dobara try karne par wahin se aage badhega.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth()) { Text("Dobara try karein") }
        OutlinedButton(onClick = vm::backToPrompt, modifier = Modifier.fillMaxWidth()) { Text("Wapas") }
    }
}

@Composable
private fun LogBox(lines: List<String>, modifier: Modifier) {
    LazyColumn(modifier) {
        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
private fun SettingsDialog(vm: AppViewModel, onClose: () -> Unit) {
    val keys = remember {
        mutableStateMapOf<String, String>().apply {
            Providers.all.forEach { put(it.id, vm.store.key(it.id)) }
        }
    }
    var models by remember { mutableStateOf(vm.store.models()) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("API keys", style = MaterialTheme.typography.titleLarge)
                Text("Keys sirf is phone mein encrypted rehti hain. Jinki key daalenge sirf wahi providers use honge.", style = MaterialTheme.typography.bodySmall)
                Providers.all.forEach { p ->
                    OutlinedTextField(
                        value = keys[p.id].orEmpty(),
                        onValueChange = { keys[p.id] = it },
                        label = { Text(p.label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Models", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Har line: tier|provider|model. Tier = planner / coder / fast. Upar wala pehle try hota hai, limit khatam ho to agla. Jitne chahein utne models jodein.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 10,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        keys.forEach { (k, v) -> vm.store.setKey(k, v) }
                        vm.store.setModels(models)
                        onClose()
                    }) { Text("Save") }
                    TextButton(onClick = { models = DefaultModels.TEXT.trim() }) { Text("Default models") }
                    TextButton(onClick = onClose) { Text("Band") }
                }
            }
        }
    }
}
