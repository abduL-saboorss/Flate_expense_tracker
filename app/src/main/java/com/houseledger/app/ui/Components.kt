package com.houseledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.houseledger.app.R
import com.houseledger.app.data.Kinds
import com.houseledger.app.domain.*
import java.time.LocalDate

@Composable fun label(id: Int, vararg args: Any) = stringResource(id, *args)
@Composable fun Title(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
@Composable fun Hint(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Page(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
private val LocalSubmitted = staticCompositionLocalOf { false }
@Composable fun Entry(value: String, change: (String) -> Unit, name: String, decimal: Boolean = false, error: String? = null, multi: Boolean = false, required: Boolean = false) {
    val issue = error ?: if (LocalSubmitted.current && required && value.isBlank()) label(R.string.required_field) else null
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(name) }, singleLine = !multi,
        minLines = if (multi) 3 else 1, isError = issue != null,
        supportingText = if (issue != null) ({ Text(issue) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable fun AmountEntry(value: String, change: (String) -> Unit) { Entry(value, change, label(R.string.amount), true, if (value.isNotEmpty() && runCatching { Money.parse(value) }.isFailure) label(R.string.invalid_amount) else null, required = true) }
fun validDate(value: String): Boolean = runCatching { LocalDate.parse(value).toString() == value && value.take(4).toInt() in 1900..9999 }.getOrDefault(false)
@Composable fun DateEntry(value: String, change: (String) -> Unit, name: String = label(R.string.date), optional: Boolean = false) { Entry(value, change, name, error = if ((!optional || value.isNotEmpty()) && !validDate(value)) label(R.string.invalid_date) else null) }
@Composable fun Notes(value: String, change: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(value.isNotEmpty()) }
    TextButton(onClick = { open = !open }) { Text(label(R.string.optional_note)) }
    if (open) Entry(value, change, label(R.string.note), multi = true)
}
@Composable fun Choice(name: String, value: String?, choices: List<Pair<String, String>>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Column { Text(name, style = MaterialTheme.typography.labelMedium); Text(choices.find { it.first == value }?.second ?: name) } }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(key); open = false }) }
        }
    }
}
@Composable fun Metric(name: String, amount: Long, prominent: Boolean = false) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(name, style = MaterialTheme.typography.labelLarge); Text(Money.format(amount), style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge) }
    }
}
@Composable fun RecordRow(title: String, subtitle: String, amount: Long? = null, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Hint(subtitle); if (amount != null) Text(Money.format(amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) } }
}
@Composable fun Empty() { Title(label(R.string.empty)); Hint(label(R.string.empty_help)) }
@Composable fun LabeledCheck(checked: Boolean, title: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = change), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp))
    }
}
@Composable fun Form(busy: Boolean, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var submitted by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        CompositionLocalProvider(LocalSubmitted provides submitted) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
        Button(onClick = { submitted = true; save() }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 52.dp)) { Text(label(if (busy) R.string.saving else R.string.save)) }
    }
}
@Composable fun kindLabel(kind: String): String = label(when (kind) { Kinds.INCOME -> R.string.income; Kinds.EXPENSE -> R.string.expense; Kinds.BILL -> R.string.settlement; Kinds.LOAN -> R.string.loan; Kinds.REPAYMENT -> R.string.repayment; else -> R.string.other })
fun problemResource(problem: Problem) = when (problem) {
    Problem.REQUIRED -> R.string.error_required; Problem.AMOUNT -> R.string.error_amount; Problem.DATE -> R.string.error_date
    Problem.MEMBERS -> R.string.error_members; Problem.SPLIT -> R.string.error_split; Problem.PERCENT -> R.string.error_percent
    Problem.RELATION -> R.string.error_relation; Problem.OVERPAYMENT -> R.string.error_overpayment; Problem.LINKED -> R.string.error_linked
    Problem.VERSION -> R.string.error_version; Problem.BACKUP -> R.string.error_backup; Problem.SELF -> R.string.error_self; Problem.REFERENCED -> R.string.error_referenced
}
