package com.houseledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.houseledger.app.R
import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable fun MonthSelector(month: YearMonth, change: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { change(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, label(R.string.month_previous)) }
        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { change(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, label(R.string.month_next)) }
    }
}
@Composable fun HomeScreen(s: Ledger, month: YearMonth, change: (YearMonth) -> Unit, nav: (String) -> Unit) {
    val totals = Accounting.totals(s, month); val me = s.household!!.myMemberId
    val end = minOf(month.atEndOfMonth(), LocalDate.now()).toString()
    val outstanding = Accounting.outstanding(s, end).filterNot { it.loan }
    Page {
        Hint(s.household!!.name); Title(label(R.string.monthly_overview)); MonthSelector(month, change)
        Metric(label(R.string.net_cash), totals.netCash, true); Hint(label(R.string.cash_help))
        Metric(label(R.string.income_total), totals.income); Metric(label(R.string.expense_total), totals.personalExpense); Metric(label(R.string.difference), totals.income - totals.personalExpense)
        Metric(label(R.string.cash_in), totals.cashIn); Metric(label(R.string.cash_out), totals.cashOut)
        Metric(label(R.string.household_spending), totals.householdSpending); Metric(label(R.string.my_cost), totals.myHouseholdCost)
        Hint(label(R.string.as_of, end)); Metric(label(R.string.unpaid), Money.sum(outstanding.filter { it.debtor == me }.map { it.remaining })); Metric(label(R.string.owed), Money.sum(outstanding.filter { it.creditor == me }.map { it.remaining }))
        Title(label(R.string.recent))
        if (s.personal.isEmpty() && s.expenses.isEmpty() && s.transfers.isEmpty()) Empty()
        val rows = s.personal.map { Triple(it.date, "personal/${it.id}", it.title to it.amount) } + s.expenses.map { Triple(it.date, "expense/${it.id}", it.title to it.amount) } + s.transfers.map { Triple(it.date, "transfer/${it.id}", "${s.member(it.fromId)} → ${s.member(it.toId)}" to it.amount) }
        rows.sortedByDescending { it.first }.take(8).forEach { (date, route, info) -> RecordRow(info.first, date, info.second) { nav("detail/$route") } }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable fun PersonalScreen(s: Ledger, month: YearMonth, change: (YearMonth) -> Unit, nav: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }; var to by rememberSaveable { mutableStateOf("") }; var filters by rememberSaveable { mutableStateOf(false) }
    val totals = Accounting.totals(s, month)
    Page {
        MonthSelector(month, change); Metric(label(R.string.income_total), totals.income); Metric(label(R.string.expense_total), totals.personalExpense)
        Entry(search, { search = it }, label(R.string.search)); TextButton(onClick = { filters = !filters }) { Text(label(R.string.filter)) }
        if (filters) {
            Choice(label(R.string.category), category, listOf("" to label(R.string.all)) + s.categories.filter { it.scope != "HOUSEHOLD" }.map { it.id to it.name }) { category = it }
            DateEntry(from, { from = it }, label(R.string.from_date), true); DateEntry(to, { to = it }, label(R.string.to_date), true)
            TextButton(onClick = { from = ""; to = ""; category = ""; search = "" }) { Text(label(R.string.clear_filters)) }
        }
        val rows = s.personal.filter { p -> (p.title + " " + p.note).contains(search, true) && (category.isEmpty() || p.categoryId == category) &&
            (if (from.isEmpty() && to.isEmpty()) YearMonth.from(LocalDate.parse(p.date)) == month else (from.isEmpty() || (validDate(from) && p.date >= from)) && (to.isEmpty() || (validDate(to) && p.date <= to))) }.sortedByDescending { it.date }
        if (rows.isEmpty()) Hint(label(R.string.no_results))
        rows.forEach { RecordRow(it.title, "${it.date} · ${s.category(it.categoryId)} · ${kindLabel(it.type)}", it.amount) { nav("detail/personal/${it.id}") } }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable fun BalanceLines(s: Ledger, memberId: String, date: String) {
    val me = s.household!!.myMemberId
    val items = Accounting.outstanding(s, date)
    listOf(false, true).forEach { loan ->
        val relevant = items.filter { it.loan == loan }
        val balance = Money.sum(relevant.filter { it.debtor == memberId && it.creditor == me }.map { it.remaining }) - Money.sum(relevant.filter { it.debtor == me && it.creditor == memberId }.map { it.remaining })
        Hint(label(if (loan) R.string.loan_balance else R.string.bill_balance))
        Text(when { balance > 0 -> label(R.string.owes_you, s.member(memberId), Money.format(balance)); balance < 0 -> label(R.string.you_owe, s.member(memberId), Money.format(-balance)); else -> label(R.string.settled, s.member(memberId)) })
    }
}

@Composable fun HouseholdScreen(s: Ledger, month: YearMonth, change: (YearMonth) -> Unit, nav: (String) -> Unit) {
    var section by rememberSaveable { mutableStateOf("bills") }
    Page {
        MonthSelector(month, change)
        Choice(label(R.string.household), section, listOf("bills" to label(R.string.bills), "members" to label(R.string.balances), "transfers" to label(R.string.transfers))) { section = it }
        when (section) {
            "members" -> {
                s.members.forEach { member ->
                    RecordRow(member.name, label(if (member.archived) R.string.archived else R.string.active)) { nav("member/${member.id}") }
                    if (member.id != s.household!!.myMemberId) BalanceLines(s, member.id, LocalDate.now().toString())
                }
                Button(onClick = { nav("form/transfer/BILL_SETTLEMENT") }) { Text(label(R.string.settlement)) }
            }
            "bills" -> {
                val rows = s.expenses.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.sortedByDescending { it.date }
                if (rows.isEmpty()) Empty()
                rows.forEach { RecordRow(it.title, "${it.date} · ${label(R.string.paid_by, s.member(it.payerId))}", it.amount) { nav("detail/expense/${it.id}") } }
            }
            else -> {
                val rows = s.transfers.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.sortedByDescending { it.date }
                if (rows.isEmpty()) Empty()
                rows.forEach { RecordRow("${s.member(it.fromId)} → ${s.member(it.toId)}", "${it.date} · ${kindLabel(it.kind)}", it.amount) { nav("detail/transfer/${it.id}") } }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable fun ReportsScreen(s: Ledger, month: YearMonth, change: (YearMonth) -> Unit, nav: (String) -> Unit, share: () -> Unit, csv: () -> Unit) {
    val totals = Accounting.totals(s, month); val end = month.atEndOfMonth().toString()
    val outstanding = Accounting.outstanding(s, end).filter { it.remaining > 0 }
    Page {
        Title(label(R.string.report_summary)); MonthSelector(month, change); Hint(label(R.string.report_help))
        Metric(label(R.string.income_total), totals.income); Metric(label(R.string.expense_total), totals.personalExpense); Metric(label(R.string.difference), totals.income - totals.personalExpense)
        Metric(label(R.string.household_spending), totals.householdSpending); Metric(label(R.string.my_cost), totals.myHouseholdCost); Metric(label(R.string.net_cash), totals.netCash); Metric(label(R.string.cash_in), totals.cashIn); Metric(label(R.string.cash_out), totals.cashOut)
        Title(label(R.string.by_category))
        s.expenses.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.groupBy { it.categoryId }.forEach { (category, rows) -> Text(label(R.string.share_row, s.category(category), Money.format(Money.sum(rows.map { it.amount })))) }
        Title(label(R.string.outstanding)); Hint(label(R.string.as_of, end))
        outstanding.forEach { item -> RecordRow(item.title.ifBlank { label(R.string.loan) }, label(R.string.obligation, s.member(item.debtor), s.member(item.creditor), Money.format(item.remaining))) {
            nav(if (item.loan) "detail/transfer/${item.id}" else "detail/expense/${s.shares.first { it.id == item.id }.expenseId}")
        } }
        if (outstanding.isEmpty()) Hint(label(R.string.share_no_outstanding))
        Button(onClick = share, modifier = Modifier.fillMaxWidth()) { Text(label(R.string.monthly_share)) }; OutlinedButton(onClick = csv, modifier = Modifier.fillMaxWidth()) { Text(label(R.string.csv_export)) }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable fun SettingsScreen(s: Ledger, vm: LedgerViewModel, nav: (String) -> Unit, theme: String, backup: () -> Unit, restore: () -> Unit) {
    Page {
        Title(label(R.string.profile)); RecordRow(s.household!!.name, s.member(s.household!!.myMemberId)) { nav("profile") }; Hint(label(R.string.currency))
        Choice(label(R.string.theme), theme, listOf("SYSTEM" to label(R.string.system), "LIGHT" to label(R.string.light), "DARK" to label(R.string.dark))) { vm.run { vm.container.preferences.theme(it) } }
        Title(label(R.string.members)); s.members.forEach { RecordRow(it.name, label(if (it.archived) R.string.archived else R.string.active)) { nav("manage/member/${it.id}") } }
        OutlinedButton(onClick = { nav("manage/member/new") }) { Text(label(R.string.add)) }
        Title(label(R.string.categories)); s.categories.forEach { RecordRow(it.name, "${if (it.scope == "HOUSEHOLD") label(R.string.household) else kindLabel(it.scope)} · ${label(if (it.archived) R.string.archived else R.string.active)}") { nav("manage/category/${it.id}") } }
        OutlinedButton(onClick = { nav("manage/category/new") }) { Text(label(R.string.add)) }
        Title(label(R.string.backup)); Hint(label(R.string.backup_help)); Button(onClick = backup) { Text(label(R.string.backup)) }; OutlinedButton(onClick = restore) { Text(label(R.string.restore)) }
    }
}

@Composable fun ManageForm(s: Ledger, vm: LedgerViewModel, type: String, id: String, busy: Boolean, done: () -> Unit) {
    val member = s.members.find { it.id == id }; val category = s.categories.find { it.id == id }
    var name by rememberSaveable { mutableStateOf(if (type == "member") member?.name.orEmpty() else category?.name.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(member?.note.orEmpty()) }; var scope by rememberSaveable { mutableStateOf(category?.scope ?: "HOUSEHOLD") }
    var archived by rememberSaveable { mutableStateOf(if (type == "member") member?.archived ?: false else category?.archived ?: false) }
    var confirm by remember { mutableStateOf(false) }; val recordId = rememberSaveable { if (id == "new") newId() else id }
    Form(busy, { vm.run(done) {
        if (type == "member") vm.container.repository.saveMember(Member(recordId, s.household!!.id, name.trim(), note, archived, member?.createdAt ?: now()))
        else vm.container.repository.saveCategory(Category(recordId, s.household!!.id, name.trim(), scope, archived, category?.createdAt ?: now()))
    } }) {
        Entry(name, { name = it }, label(R.string.name), required = true); if (type == "member") Notes(note) { note = it }
        if (type == "category" && category == null) Choice(label(R.string.scope), scope, listOf("HOUSEHOLD" to label(R.string.household), Kinds.INCOME to label(R.string.income), Kinds.EXPENSE to label(R.string.expense))) { scope = it }
        if (recordId != s.household!!.myMemberId) LabeledCheck(archived, label(R.string.archived)) { archived = it }
        if (id != "new") {
            OutlinedButton(onClick = { confirm = true }) { Text(label(R.string.delete)) }
        }
    }
    if (confirm) DeleteDialog({ confirm = false }) { vm.run(done) { vm.container.repository.delete(type, id) }; confirm = false }
}
@Composable fun ProfileForm(s: Ledger, vm: LedgerViewModel, busy: Boolean, done: () -> Unit) {
    var home by rememberSaveable { mutableStateOf(s.household!!.name) }; var me by rememberSaveable { mutableStateOf(s.member(s.household!!.myMemberId)) }
    Form(busy, { vm.run(done) { vm.container.repository.saveProfile(home, me) } }) { Entry(me, { me = it }, label(R.string.my_name), required = true); Entry(home, { home = it }, label(R.string.household_name), required = true) }
}
@Composable fun DeleteDialog(cancel: () -> Unit, delete: () -> Unit) { AlertDialog(onDismissRequest = cancel, title = { Text(label(R.string.confirm_delete)) }, text = { Text(label(R.string.delete_explanation)) }, confirmButton = { TextButton(onClick = delete) { Text(label(R.string.delete)) } }, dismissButton = { TextButton(onClick = cancel) { Text(label(R.string.cancel)) } }) }
