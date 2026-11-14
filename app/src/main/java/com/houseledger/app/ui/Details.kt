package com.houseledger.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.houseledger.app.R
import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import java.time.LocalDate

@Composable fun DetailScreen(s: Ledger, type: String, id: String, vm: LedgerViewModel, nav: (String) -> Unit, back: () -> Unit, share: (String, String) -> Unit, messages: Messages) {
    var confirm by remember { mutableStateOf(false) }
    val p = s.personal.find { it.id == id }; val b = s.expenses.find { it.id == id }; val t = s.transfers.find { it.id == id }
    if ((type == "personal" && p == null) || (type == "expense" && b == null) || (type == "transfer" && t == null)) return
    Page {
        when (type) {
            "personal" -> { p!!; Title(p.title); Metric(kindLabel(p.type), p.amount); Text(p.date); Text(s.category(p.categoryId)); if (p.note.isNotBlank()) Text(p.note) }
            "expense" -> {
                b!!; Title(b.title); Metric(label(R.string.total), b.amount); Text(b.date); Text(s.category(b.categoryId)); Text(label(R.string.paid_by, s.member(b.payerId)))
                s.shares.filter { it.expenseId == id }.forEach { Text(label(R.string.share_row, s.member(it.memberId), Money.format(it.amount))) }
                if (b.note.isNotBlank()) Text(b.note)
                Title(label(R.string.history))
                val outstanding = Accounting.outstanding(s, LocalDate.now().toString()).filter { item -> s.shares.any { it.expenseId == id && it.id == item.id } }
                outstanding.forEach { Text(label(R.string.obligation, s.member(it.debtor), s.member(it.creditor), Money.format(it.remaining))) }
                val shares = s.shares.filter { it.expenseId == id }.map { it.id }
                s.allocations.filter { it.shareId in shares }.forEach { a -> val transfer = s.transfers.first { it.id == a.transferId }; RecordRow("${s.member(transfer.fromId)} → ${s.member(transfer.toId)}", transfer.date, a.amount) { nav("detail/transfer/${transfer.id}") } }
                Button(onClick = { share(messages.bill(s, id), b.note) }) { Text(label(R.string.bill_share)) }; Hint(label(R.string.refresh_share))
                OutlinedButton(onClick = { nav("form/transfer/BILL_SETTLEMENT") }) { Text(label(R.string.settlement)) }
            }
            else -> {
                t!!; Title(kindLabel(t.kind)); Metric(label(R.string.amount), t.amount); Text("${s.member(t.fromId)} → ${s.member(t.toId)}"); Text(t.date); if (t.note.isNotBlank()) Text(t.note)
                if (t.kind == Kinds.LOAN) {
                    Accounting.outstanding(s, maxOf(t.date, LocalDate.now().toString())).find { it.id == id }?.let { Metric(label(R.string.original), it.original); Metric(label(R.string.paid), it.paid); Metric(label(R.string.remaining), it.remaining) }
                    s.allocations.filter { it.loanId == id }.forEach { a -> val payment = s.transfers.first { it.id == a.transferId }; RecordRow(label(R.string.repayment), payment.date, a.amount) { nav("detail/transfer/${payment.id}") } }
                    Button(onClick = { nav("form/transfer/LOAN_REPAYMENT") }) { Text(label(R.string.repayment)) }
                }
                s.allocations.filter { it.transferId == id }.forEach { a ->
                    val expenseId = s.shares.find { it.id == a.shareId }?.expenseId
                    val title = expenseId?.let { e -> s.expenses.first { it.id == e }.title } ?: s.transfers.find { it.id == a.loanId }?.note.orEmpty()
                    RecordRow(title.ifBlank { label(R.string.loan) }, label(R.string.allocation), a.amount) { nav(if (expenseId != null) "detail/expense/$expenseId" else "detail/transfer/${a.loanId}") }
                }
                if (t.kind in listOf(Kinds.BILL, Kinds.REPAYMENT)) Button(onClick = { share(messages.confirmation(s, id), t.note) }) { Text(label(R.string.confirmation)) }
            }
        }
        OutlinedButton(onClick = { nav("edit/$type/$id") }) { Text(label(R.string.edit)) }; OutlinedButton(onClick = { confirm = true }) { Text(label(R.string.delete)) }
    }
    if (confirm) DeleteDialog({ confirm = false }) { confirm = false; vm.run(back) { vm.container.repository.delete(type, id) } }
}

@Composable fun MemberScreen(s: Ledger, id: String, nav: (String) -> Unit, share: (String, String) -> Unit, messages: Messages) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    Page {
        Title(s.member(id)); DateEntry(date, { date = it }); if (!validDate(date)) return@Page
        if (id != s.household!!.myMemberId) BalanceLines(s, id, date)
        Title(label(R.string.history))
        val items = Accounting.outstanding(s, date).filter { it.debtor == id || it.creditor == id }.sortedBy { it.date }
        items.forEach { item ->
            val bill = s.shares.find { it.id == item.id }?.expenseId
            RecordRow(item.title.ifBlank { label(R.string.loan) }, "${item.date} · ${label(R.string.obligation, s.member(item.debtor), s.member(item.creditor), Money.format(item.remaining))}", item.original) { nav(if (item.loan) "detail/transfer/${item.id}" else "detail/expense/$bill") }
            Text(label(R.string.share_row, label(R.string.paid), Money.format(item.paid))); Text(label(R.string.share_row, label(R.string.remaining), Money.format(item.remaining)))
        }
        Title(label(R.string.running_history)); Hint(label(R.string.running_help))
        Accounting.memberActivity(s, id, date).forEach { event ->
            RecordRow(event.title, event.date, event.amount) { nav(event.route) }
            Text(label(R.string.running_bill, Money.format(event.billBalance))); Text(label(R.string.running_loan, Money.format(event.loanBalance)))
        }
        Button(onClick = { share(messages.member(s, id, date), "") }) { Text(label(R.string.statement)) }
        OutlinedButton(onClick = { nav("form/transfer/BILL_SETTLEMENT") }) { Text(label(R.string.settlement)) }
    }
}
