package com.houseledger.app.ui

import android.content.Context
import com.houseledger.app.R
import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import java.time.LocalDate
import java.time.YearMonth

class Messages(private val context: Context) {
    private fun t(id: Int, vararg args: Any) = context.getString(id, *args)
    fun bill(s: Ledger, id: String): String {
        val b = s.expenses.first { it.id == id }
        return t(R.string.share_bill_header, b.title, b.date, Money.format(b.amount), s.member(b.payerId)) + "\n" + s.shares.filter { it.expenseId == id }.joinToString("\n") { t(R.string.share_person_share, s.member(it.memberId), Money.format(it.amount)) }
    }
    fun month(s: Ledger, month: YearMonth): String {
        val outstanding = Accounting.outstanding(s, month.atEndOfMonth().toString()).filter { !it.loan && it.remaining > 0 }
        return buildString {
            appendLine(t(R.string.share_month_header, s.household!!.name, month.toString(), Money.format(Accounting.totals(s, month).householdSpending)))
            s.expenses.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.groupBy { it.categoryId }.forEach { (id, bills) -> appendLine(t(R.string.share_row, s.category(id), Money.format(Money.sum(bills.map { it.amount })))) }
            appendLine(); appendLine(t(R.string.share_outstanding, month.atEndOfMonth().toString()))
            outstanding.forEach { appendLine(t(R.string.obligation, s.member(it.debtor), s.member(it.creditor), Money.format(it.remaining))) }
            if (outstanding.isEmpty()) append(t(R.string.share_no_outstanding))
        }.trim()
    }
    fun member(s: Ledger, member: String, date: String): String = buildString {
        appendLine(t(R.string.share_statement_header, s.member(member), date))
        val items = Accounting.outstanding(s, date).filter { it.debtor == member || it.creditor == member }
        listOf(false, true).forEach { loan ->
            appendLine(t(if (loan) R.string.loan_balance else R.string.bill_balance))
            val relevant = items.filter { it.loan == loan }
            relevant.map { if (it.debtor == member) it.creditor else it.debtor }.distinct().forEach { other ->
                val balance = Money.sum(relevant.filter { it.debtor == member && it.creditor == other }.map { it.remaining }) - Money.sum(relevant.filter { it.creditor == member && it.debtor == other }.map { it.remaining })
                if (balance > 0) appendLine(t(R.string.obligation, s.member(member), s.member(other), Money.format(balance)))
                else if (balance < 0) appendLine(t(R.string.obligation, s.member(other), s.member(member), Money.format(-balance)))
                else appendLine(t(R.string.settled, s.member(other)))
            }
        }
        items.forEach {
            appendLine(t(R.string.share_item, if (it.loan) t(R.string.loan) else it.title, it.date, s.member(it.debtor), s.member(it.creditor), Money.format(it.original), Money.format(it.paid), Money.format(it.remaining)))
        }
        // No private personal transactions or unrelated member entries.
        s.transfers.filter { it.date <= date && (it.fromId == member || it.toId == member) }.sortedBy { it.date }.forEach {
            appendLine(t(R.string.share_transfer, it.date, s.member(it.fromId), s.member(it.toId), Money.format(it.amount)))
        }
    }.trim()
    fun confirmation(s: Ledger, id: String): String {
        val payment = s.transfers.first { it.id == id }
        val outstanding = Accounting.outstanding(s, payment.date)
        return buildString {
            appendLine(t(R.string.share_confirmation, s.member(payment.fromId), s.member(payment.toId), Money.format(payment.amount), payment.date))
            s.allocations.filter { it.transferId == id }.forEach { a -> outstanding.find { it.id == (a.shareId ?: a.loanId) }?.let { appendLine(t(R.string.share_remaining, if (it.loan) t(R.string.loan) else it.title, Money.format(it.remaining))) } }
        }.trim()
    }
    fun csv(s: Ledger, month: YearMonth): String {
        fun cell(value: String): String {
            // Spreadsheet formula injection protection while preserving readable user text.
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" + value else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        return buildString {
            append('\uFEFF'); appendLine(t(R.string.csv_header))
            fun row(vararg values: String) { appendLine(values.joinToString(",", transform = ::cell)) }
            val totals = Accounting.totals(s, month)
            listOf(R.string.income_total to totals.income, R.string.expense_total to totals.personalExpense, R.string.household_spending to totals.householdSpending, R.string.my_cost to totals.myHouseholdCost, R.string.cash_in to totals.cashIn, R.string.cash_out to totals.cashOut, R.string.net_cash to totals.netCash).forEach { (name, value) -> row(t(R.string.csv_summary), month.toString(), t(name), "", "", "", Money.input(value), "", "", "") }
            s.personal.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.forEach { row(t(R.string.csv_personal), it.date, it.title, s.category(it.categoryId), if (it.type == Kinds.INCOME) "" else s.member(s.household!!.myMemberId), if (it.type == Kinds.INCOME) s.member(s.household!!.myMemberId) else "", Money.input(it.amount), "", "", "") }
            s.expenses.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.forEach { row(t(R.string.csv_household), it.date, it.title, s.category(it.categoryId), s.member(it.payerId), "", Money.input(it.amount), "", "", "") }
            s.transfers.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.forEach { row(t(R.string.csv_transfer), it.date, t(when (it.kind) { Kinds.BILL -> R.string.settlement; Kinds.LOAN -> R.string.loan; Kinds.REPAYMENT -> R.string.repayment; else -> R.string.other }), "", s.member(it.fromId), s.member(it.toId), Money.input(it.amount), "", "", "") }
            Accounting.outstanding(s, month.atEndOfMonth().toString()).filter { it.remaining > 0 }.forEach { row(t(if (it.loan) R.string.csv_loan_balance else R.string.csv_bill_balance), it.date, it.title, "", s.member(it.debtor), s.member(it.creditor), "", Money.input(it.original), Money.input(it.paid), Money.input(it.remaining)) }
        }
    }
}
