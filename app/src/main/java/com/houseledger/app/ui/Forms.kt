package com.houseledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.houseledger.app.R
import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import java.time.LocalDate

@Composable fun Setup(vm: LedgerViewModel, busy: Boolean) {
    val context = LocalContext.current
    var myName by rememberSaveable { mutableStateOf("") }; var home by rememberSaveable { mutableStateOf("") }; var members by rememberSaveable { mutableStateOf("") }
    Form(busy, {
        vm.run { vm.container.repository.setup(myName, home, members.lines(), mapOf(
            Kinds.INCOME to listOf(R.string.cat_salary, R.string.cat_other).map(context::getString),
            Kinds.EXPENSE to listOf(R.string.cat_food, R.string.cat_transport, R.string.cat_shopping, R.string.cat_health, R.string.cat_other).map(context::getString),
            "HOUSEHOLD" to listOf(R.string.cat_rent, R.string.cat_electricity, R.string.cat_gas, R.string.cat_water, R.string.cat_internet, R.string.cat_groceries, R.string.cat_other).map(context::getString))) }
    }) { Title(label(R.string.welcome)); Hint(label(R.string.setup_intro)); Entry(myName, { myName = it }, label(R.string.my_name), required = true); Entry(home, { home = it }, label(R.string.household_name), required = true); Entry(members, { members = it }, label(R.string.extra_members), multi = true); Hint(label(R.string.offline)) }
}

@Composable fun PersonalForm(s: Ledger, vm: LedgerViewModel, type: String, id: String?, done: () -> Unit, busy: Boolean) {
    val old = s.personal.find { it.id == id }; val actualType = old?.type ?: type
    var amount by rememberSaveable { mutableStateOf(old?.amount?.let(Money::input) ?: "") }
    var title by rememberSaveable { mutableStateOf(old?.title ?: "") }; var note by rememberSaveable { mutableStateOf(old?.note ?: "") }
    var date by rememberSaveable { mutableStateOf(old?.date ?: LocalDate.now().toString()) }
    var category by rememberSaveable { mutableStateOf(old?.categoryId ?: s.categories.firstOrNull { !it.archived && it.scope == actualType }?.id.orEmpty()) }
    val recordId = rememberSaveable { old?.id ?: newId() }
    Form(busy, { vm.run(done) { vm.container.repository.savePersonal(PersonalTransaction(recordId, actualType, Money.parse(amount), date, category, title.trim(), note, old?.createdAt ?: now())) } }) {
        Title(kindLabel(actualType)); AmountEntry(amount) { amount = it }; Entry(title, { title = it }, label(R.string.title), required = true); DateEntry(date, { date = it })
        Choice(label(R.string.category), category, s.categories.filter { it.scope == actualType && (!it.archived || it.id == category) }.map { it.id to it.name }) { category = it }
        Notes(note) { note = it }
    }
}

@Composable fun ExpenseForm(s: Ledger, vm: LedgerViewModel, id: String?, done: () -> Unit, busy: Boolean) {
    val old = s.expenses.find { it.id == id }; val oldShares = s.shares.filter { it.expenseId == id }
    val recordId = rememberSaveable { old?.id ?: newId() }
    var amount by rememberSaveable { mutableStateOf(old?.amount?.let(Money::input) ?: "") }; var title by rememberSaveable { mutableStateOf(old?.title ?: "") }
    var note by rememberSaveable { mutableStateOf(old?.note ?: "") }; var date by rememberSaveable { mutableStateOf(old?.date ?: LocalDate.now().toString()) }
    var payer by rememberSaveable { mutableStateOf(old?.payerId ?: s.household!!.myMemberId) }
    var category by rememberSaveable { mutableStateOf(old?.categoryId ?: s.categories.firstOrNull { !it.archived && it.scope == "HOUSEHOLD" }?.id.orEmpty()) }
    var method by rememberSaveable { mutableStateOf(old?.splitMethod ?: Kinds.EQUAL) }
    var selected by rememberSaveable { mutableStateOf(oldShares.map { it.memberId }) }
    var inputs by rememberSaveable { mutableStateOf(oldShares.associate { it.memberId to if (old?.splitMethod == Kinds.PERCENT) Money.input((it.basisPoints ?: 0).toLong()) else Money.input(it.amount) }) }
    val members = s.members.filter { !it.archived || it.id in selected || it.id == payer }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    // Keep persisted order for unchanged bills so rounding never changes while editing a title.
    val ordered = oldShares.map { it.memberId }.filter { it in selected } + members.map { it.id }.filter { it in selected && oldShares.none { row -> row.memberId == it } }
    val preview = runCatching {
        val total = Money.parse(amount)
        when (method) { Kinds.EQUAL -> Splits.equal(total, ordered); Kinds.EXACT -> Splits.exact(total, ordered.associateWith { Money.parse(inputs[it].orEmpty(), true) }); else -> Splits.percentages(total, ordered.associateWith { Splits.percentageInput(inputs[it].orEmpty()) }) }
    }
    val linked = s.allocations.any { a -> oldShares.any { it.id == a.shareId } }
    Form(busy, { vm.run(done) {
        val shares = preview.getOrElse { throw (it as? LedgerException ?: LedgerException(Problem.SPLIT)) }
        vm.container.repository.saveExpense(HouseholdExpense(recordId, Money.parse(amount), date, category, title.trim(), payer, method, note, old?.createdAt ?: now()), shares.map { (member, value) ->
            val existing = oldShares.find { it.memberId == member }
            ExpenseShare(existing?.id ?: "$recordId:$member", recordId, member, value, if (method == Kinds.PERCENT) Splits.percentageInput(inputs[member].orEmpty()) else null, existing?.createdAt ?: now())
        })
    } }) {
        Title(label(R.string.shared_expense)); if (linked) Hint(label(R.string.form_locked))
        AmountEntry(amount) { amount = it }; Entry(title, { title = it }, label(R.string.title), required = true)
        Choice(label(R.string.payer), payer, members.map { it.id to it.name }) { payer = it }
        Title(label(R.string.participants)); TextButton(onClick = { selected = s.members.filterNot { it.archived }.map { it.id } }) { Text(label(R.string.select_all)) }
        members.forEach { member ->
            LabeledCheck(member.id in selected, member.name) { selected = if (it) selected + member.id else selected - member.id }
        }
        Choice(label(R.string.split), method, listOf(Kinds.EQUAL to label(R.string.equal), Kinds.EXACT to label(R.string.exact), Kinds.PERCENT to label(R.string.percentage))) { method = it }
        if (method != Kinds.EQUAL) ordered.forEach { member -> Entry(inputs[member].orEmpty(), { inputs = inputs + (member to it) }, label(if (method == Kinds.PERCENT) R.string.percent_field else R.string.share_field, s.member(member)), decimal = true) }
        if (method == Kinds.EXACT) Hint(label(R.string.share_total, Money.format(ordered.sumOf { runCatching { Money.parse(inputs[it].orEmpty(), true) }.getOrDefault(0) })))
        if (method == Kinds.PERCENT) Hint(label(R.string.percent_total, Money.input(ordered.sumOf { runCatching { Splits.percentageInput(inputs[it].orEmpty()).toLong() }.getOrDefault(0) })))
        Title(label(R.string.review)); Hint(label(R.string.paid_by, s.member(payer)))
        preview.getOrNull()?.forEach { (member, value) -> Text(label(R.string.share_row, s.member(member), Money.format(value))) }
        preview.getOrNull()?.let { Text(label(R.string.share_row, label(R.string.total), Money.format(Money.sum(it.values)))) }
        if (preview.isFailure && amount.isNotEmpty()) Hint(label(if (method == Kinds.PERCENT) R.string.error_percent else R.string.error_split))
        DateEntry(date, { date = it }); Choice(label(R.string.category), category, s.categories.filter { it.scope == "HOUSEHOLD" && (!it.archived || it.id == category) }.map { it.id to it.name }) { category = it }; Notes(note) { note = it }
    }
}

@Composable fun TransferForm(s: Ledger, vm: LedgerViewModel, initialKind: String, id: String?, done: () -> Unit, busy: Boolean) {
    val old = s.transfers.find { it.id == id }; val recordId = rememberSaveable { old?.id ?: newId() }
    var kind by rememberSaveable { mutableStateOf(old?.kind ?: initialKind) }; var amount by rememberSaveable { mutableStateOf(old?.amount?.let(Money::input) ?: "") }
    var from by rememberSaveable { mutableStateOf(old?.fromId ?: s.household!!.myMemberId) }; var to by rememberSaveable { mutableStateOf(old?.toId ?: s.members.firstOrNull { it.id != from && !it.archived }?.id.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(old?.date ?: LocalDate.now().toString()) }; var note by rememberSaveable { mutableStateOf(old?.note ?: "") }
    var target by rememberSaveable { mutableStateOf(s.allocations.filter { it.transferId == id }.singleOrNull()?.let { it.shareId ?: it.loanId } ?: "") }
    val clean = s.copy(transfers = s.transfers.filterNot { it.id == id }, allocations = s.allocations.filterNot { it.transferId == id })
    val targets = if (validDate(date)) Accounting.outstanding(clean, date).filter { it.debtor == from && it.creditor == to && it.remaining > 0 && it.loan == (kind == Kinds.REPAYMENT) } else emptyList()
    val preview = runCatching { val t = Transfer(recordId, kind, from, to, Money.parse(amount), date, note); if (kind in listOf(Kinds.BILL, Kinds.REPAYMENT)) Accounting.allocations(clean, t, target.ifEmpty { null }) else emptyList() }
    Form(busy, { vm.run(done) { vm.container.repository.saveTransfer(Transfer(recordId, kind, from, to, Money.parse(amount), date, note, old?.createdAt ?: now()), target.ifEmpty { null }) } }) {
        Title(label(R.string.transfers)); Choice(label(R.string.record_type), kind, listOf(Kinds.BILL, Kinds.LOAN, Kinds.REPAYMENT, Kinds.OTHER).map { it to kindLabel(it) }) { kind = it; target = "" }
        AmountEntry(amount) { amount = it }
        val members = s.members.filter { !it.archived || it.id in listOf(from, to) || targets.any { t -> t.debtor == it.id || t.creditor == it.id } || s.shares.any { share -> share.memberId == it.id } || s.transfers.any { t -> t.fromId == it.id || t.toId == it.id } }
        Choice(label(R.string.sender), from, members.map { it.id to it.name }) { from = it; target = "" }
        Choice(label(R.string.receiver), to, members.map { it.id to it.name }) { to = it; target = "" }; DateEntry(date, { date = it })
        if (kind in listOf(Kinds.BILL, Kinds.REPAYMENT)) {
            val options = (if (kind == Kinds.BILL) listOf("" to label(R.string.oldest)) else listOf("" to label(R.string.select_loan))) + targets.map { it.id to "${it.title.ifBlank { s.member(it.creditor) }} · ${it.date} · ${Money.format(it.remaining)}" }
            Choice(label(R.string.apply_to), target, options) { target = it }
            Title(label(R.string.allocation))
            preview.getOrNull()?.forEach { allocation -> val item = targets.find { it.id == (allocation.shareId ?: allocation.loanId) }; Text(label(R.string.share_row, item?.title.orEmpty().ifBlank { label(R.string.loan) }, Money.format(allocation.amount))); if (item != null) Text(label(R.string.share_row, label(R.string.remaining), Money.format(item.remaining - allocation.amount))) }
            if (preview.isFailure && amount.isNotEmpty()) Hint(label(if (kind == Kinds.REPAYMENT && target.isEmpty()) R.string.select_loan else R.string.error_overpayment))
        }
        Notes(note) { note = it }
    }
}
