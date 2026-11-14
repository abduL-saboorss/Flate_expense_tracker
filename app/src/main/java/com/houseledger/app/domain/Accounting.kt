package com.houseledger.app.domain

import com.houseledger.app.data.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.text.NumberFormat
import java.util.Locale

enum class Problem { REQUIRED, AMOUNT, DATE, MEMBERS, SPLIT, PERCENT, RELATION, OVERPAYMENT, LINKED, VERSION, BACKUP, SELF, REFERENCED }
class LedgerException(val problem: Problem, val detail: String = "") : IllegalArgumentException(problem.name)
fun ensure(test: Boolean, problem: Problem, detail: String = "") { if (!test) throw LedgerException(problem, detail) }

object Money {
    const val MAX = 100_000_000_000_000L
    fun parse(input: String, allowZero: Boolean = false): Long {
        ensure(input.matches(Regex("[0-9]+(\\.[0-9]{1,2})?")), Problem.AMOUNT)
        val value = try { BigDecimal(input).movePointRight(2).longValueExact() } catch (_: Exception) { throw LedgerException(Problem.AMOUNT) }
        ensure(value in (if (allowZero) 0L else 1L)..MAX, Problem.AMOUNT)
        return value
    }
    fun input(value: Long): String = BigDecimal.valueOf(value, 2).toPlainString()
    fun format(value: Long): String = "Rs " + NumberFormat.getNumberInstance(Locale("en", "PK")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(value, 2))
    fun sum(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)
}

object Splits {
    // The caller supplies a stable member order. Leftover minor units go to that order.
    fun equal(total: Long, members: List<String>): Map<String, Long> {
        ensure(total in 1..Money.MAX && members.isNotEmpty() && members.distinct().size == members.size, Problem.SPLIT)
        return members.mapIndexed { index, id -> id to total / members.size + if (index < total % members.size) 1 else 0 }.toMap()
    }
    fun percentageInput(text: String): Int {
        val result = Money.parse(text, true)
        ensure(result <= 10000, Problem.PERCENT)
        return result.toInt()
    }
    fun percentages(total: Long, weights: Map<String, Int>): Map<String, Long> {
        ensure(total in 1..Money.MAX && weights.isNotEmpty() && weights.values.all { it in 0..10000 } && weights.values.sum() == 10000, Problem.PERCENT)
        // The quotient is bounded by total (<= MAX), so conversion is exact on API 26 too.
        val amounts = weights.mapValues { (_, weight) -> BigInteger.valueOf(total).multiply(BigInteger.valueOf(weight.toLong())).divide(BigInteger.valueOf(10000)).toLong() }.toMutableMap()
        var remainder = total - Money.sum(amounts.values)
        // Largest fractional remainders first; original order breaks ties deterministically.
        weights.entries.sortedByDescending { BigInteger.valueOf(total).multiply(BigInteger.valueOf(it.value.toLong())).mod(BigInteger.valueOf(10000)) }.forEach {
            if (remainder > 0) { amounts[it.key] = amounts.getValue(it.key) + 1; remainder-- }
        }
        return amounts
    }
    fun exact(total: Long, amounts: Map<String, Long>): Map<String, Long> {
        ensure(total in 1..Money.MAX && amounts.isNotEmpty() && amounts.values.all { it >= 0 } && Money.sum(amounts.values) == total, Problem.SPLIT)
        return amounts
    }
}

data class Outstanding(val id: String, val debtor: String, val creditor: String, val title: String,
    val date: String, val original: Long, val paid: Long, val loan: Boolean) { val remaining: Long get() = original - paid }
data class Totals(val income: Long, val personalExpense: Long, val householdSpending: Long,
    val myHouseholdCost: Long, val cashIn: Long, val cashOut: Long) { val netCash get() = cashIn - cashOut }
data class MemberActivity(val date: String, val title: String, val route: String, val amount: Long,
    val billBalance: Long, val loanBalance: Long)

object Accounting {
    fun memberActivity(s: Ledger, member: String, asOf: String): List<MemberActivity> {
        data class Event(val date: String, val order: Int, val id: String, val title: String, val route: String, val amount: Long, val bills: Long = 0, val loans: Long = 0)
        val events = mutableListOf<Event>()
        s.expenses.filter { it.date <= asOf }.forEach { bill ->
            s.shares.filter { it.expenseId == bill.id && it.memberId != bill.payerId && (it.memberId == member || bill.payerId == member) }.forEach { share ->
                events += Event(bill.date, 0, share.id, bill.title, "detail/expense/${bill.id}", share.amount, bills = if (bill.payerId == member) share.amount else -share.amount)
            }
        }
        s.transfers.filter { it.date <= asOf && (it.fromId == member || it.toId == member) }.forEach { t ->
            val loan = if (t.kind == Kinds.LOAN) (if (t.fromId == member) t.amount else -t.amount) else if (t.kind == Kinds.REPAYMENT) (if (t.fromId == member) t.amount else -t.amount) else 0
            val bill = if (t.kind == Kinds.BILL) (if (t.fromId == member) t.amount else -t.amount) else 0
            events += Event(t.date, 1, t.id, "${s.member(t.fromId)} → ${s.member(t.toId)}", "detail/transfer/${t.id}", t.amount, bill, loan)
        }
        var bills = 0L; var loans = 0L
        return events.sortedWith(compareBy({ it.date }, { it.order }, { it.id })).map { event -> bills = Math.addExact(bills, event.bills); loans = Math.addExact(loans, event.loans); MemberActivity(event.date, event.title, event.route, event.amount, bills, loans) }
    }
    fun outstanding(s: Ledger, asOf: String): List<Outstanding> {
        val transfers = s.transfers.associateBy { it.id }
        val allocations = s.allocations.filter { transfers.getValue(it.transferId).date <= asOf }
        val bills = s.expenses.associateBy { it.id }
        val shares = s.shares.mapNotNull { share ->
            val bill = bills.getValue(share.expenseId)
            if (bill.date > asOf || share.memberId == bill.payerId) null
            else Outstanding(share.id, share.memberId, bill.payerId, bill.title, bill.date, share.amount,
                Money.sum(allocations.filter { it.shareId == share.id }.map { it.amount }), false)
        }
        return shares + s.transfers.filter { it.kind == Kinds.LOAN && it.date <= asOf }.map { loan ->
            Outstanding(loan.id, loan.toId, loan.fromId, loan.note, loan.date, loan.amount,
                Money.sum(allocations.filter { it.loanId == loan.id }.map { it.amount }), true)
        }
    }
    fun allocations(s: Ledger, transfer: Transfer, selectedId: String? = null): List<PaymentAllocation> {
        val loan = transfer.kind == Kinds.REPAYMENT
        ensure(transfer.kind in listOf(Kinds.BILL, Kinds.REPAYMENT), Problem.RELATION)
        if (loan) ensure(selectedId != null, Problem.REQUIRED)
        val eligible = outstanding(s, transfer.date).filter { it.loan == loan && it.debtor == transfer.fromId && it.creditor == transfer.toId && it.remaining > 0 && (selectedId == null || it.id == selectedId) }.sortedWith(compareBy({ it.date }, { it.id }))
        ensure(transfer.amount > 0 && transfer.amount <= Money.sum(eligible.map { it.remaining }), Problem.OVERPAYMENT)
        var remaining = transfer.amount
        return eligible.mapNotNull {
            val amount = minOf(remaining, it.remaining); remaining -= amount
            if (amount == 0L) null else PaymentAllocation(transferId = transfer.id, shareId = if (loan) null else it.id, loanId = if (loan) it.id else null, amount = amount)
        }
    }
    fun totals(s: Ledger, month: YearMonth): Totals {
        val me = s.household?.myMemberId
        fun inMonth(date: String) = YearMonth.from(LocalDate.parse(date)) == month
        val personal = s.personal.filter { inMonth(it.date) }
        val bills = s.expenses.filter { inMonth(it.date) }
        val transfers = s.transfers.filter { inMonth(it.date) }
        val income = Money.sum(personal.filter { it.type == Kinds.INCOME }.map { it.amount })
        val expense = Money.sum(personal.filter { it.type == Kinds.EXPENSE }.map { it.amount })
        return Totals(income, expense, Money.sum(bills.map { it.amount }),
            Money.sum(s.shares.filter { it.memberId == me && bills.any { b -> b.id == it.expenseId } }.map { it.amount }),
            Math.addExact(income, Money.sum(transfers.filter { it.toId == me }.map { it.amount })),
            Math.addExact(Math.addExact(expense, Money.sum(bills.filter { it.payerId == me }.map { it.amount })), Money.sum(transfers.filter { it.fromId == me }.map { it.amount })))
    }
}

object LedgerValidator {
    fun validate(s: Ledger, allowEmpty: Boolean = false) {
        if (allowEmpty && s == Ledger()) return
        ensure(s.households.size == 1, Problem.BACKUP)
        val h = s.households.single()
        fun text(value: String) = ensure(value.isNotBlank() && value.length <= 2000, Problem.REQUIRED)
        fun ids(values: List<String>) { ensure(values.distinct().size == values.size && values.all { it.isNotBlank() && it.length <= 100 }, Problem.BACKUP) }
        fun amount(value: Long) = ensure(value in 1..Money.MAX, Problem.AMOUNT)
        fun date(value: String) { ensure(runCatching { LocalDate.parse(value).toString() == value && value.take(4).toInt() in 1900..9999 }.getOrDefault(false), Problem.DATE) }
        ids(s.households.map { it.id }); ids(s.members.map { it.id }); ids(s.categories.map { it.id }); ids(s.personal.map { it.id }); ids(s.expenses.map { it.id }); ids(s.shares.map { it.id }); ids(s.transfers.map { it.id }); ids(s.allocations.map { it.id })
        val audits = s.households.map { it.createdAt to it.updatedAt } + s.members.map { it.createdAt to it.updatedAt } + s.categories.map { it.createdAt to it.updatedAt } + s.personal.map { it.createdAt to it.updatedAt } + s.expenses.map { it.createdAt to it.updatedAt } + s.shares.map { it.createdAt to it.updatedAt } + s.transfers.map { it.createdAt to it.updatedAt } + s.allocations.map { it.createdAt to it.updatedAt }
        ensure(audits.all { it.first >= 0 && it.second >= 0 }, Problem.BACKUP)
        text(h.name); ensure(h.currency == "PKR" && s.members.any { it.id == h.myMemberId && !it.archived }, Problem.SELF)
        val members = s.members.associateBy { it.id }; val cats = s.categories.associateBy { it.id }
        s.members.forEach { text(it.name); ensure(it.householdId == h.id, Problem.RELATION) }
        s.categories.forEach { text(it.name); ensure(it.householdId == h.id && it.scope in listOf("INCOME", "EXPENSE", "HOUSEHOLD"), Problem.RELATION) }
        s.personal.forEach { text(it.title); amount(it.amount); date(it.date); ensure(it.type in listOf(Kinds.INCOME, Kinds.EXPENSE) && cats[it.categoryId]?.scope == it.type, Problem.RELATION) }
        val bills = s.expenses.associateBy { it.id }; val shares = s.shares.associateBy { it.id }; val transfers = s.transfers.associateBy { it.id }
        s.expenses.forEach { bill ->
            text(bill.title); amount(bill.amount); date(bill.date)
            ensure(members.containsKey(bill.payerId) && cats[bill.categoryId]?.scope == "HOUSEHOLD", Problem.RELATION)
            ensure(bill.splitMethod in listOf(Kinds.EQUAL, Kinds.EXACT, Kinds.PERCENT), Problem.SPLIT)
            val rows = s.shares.filter { it.expenseId == bill.id }
            ensure(rows.isNotEmpty() && rows.map { it.memberId }.distinct().size == rows.size && Money.sum(rows.map { it.amount }) == bill.amount, Problem.SPLIT)
            if (bill.splitMethod == Kinds.PERCENT) {
                ensure(rows.all { it.basisPoints != null }, Problem.PERCENT)
                val calculated = Splits.percentages(bill.amount, rows.associate { it.memberId to it.basisPoints!! })
                ensure(rows.all { calculated[it.memberId] == it.amount }, Problem.PERCENT)
            }
            if (bill.splitMethod == Kinds.EQUAL) ensure(rows.map { it.amount }.sorted() == Splits.equal(bill.amount, rows.map { it.memberId }).values.sorted(), Problem.SPLIT)
        }
        s.shares.forEach { ensure(bills.containsKey(it.expenseId) && members.containsKey(it.memberId) && it.amount in 0..Money.MAX, Problem.RELATION) }
        s.transfers.forEach { t ->
            amount(t.amount); date(t.date); ensure(t.fromId != t.toId && members.containsKey(t.fromId) && members.containsKey(t.toId), Problem.MEMBERS)
            ensure(t.kind in listOf(Kinds.BILL, Kinds.LOAN, Kinds.REPAYMENT, Kinds.OTHER), Problem.RELATION)
            val rows = s.allocations.filter { it.transferId == t.id }
            if (t.kind in listOf(Kinds.BILL, Kinds.REPAYMENT)) {
                ensure(rows.isNotEmpty() && Money.sum(rows.map { it.amount }) == t.amount, Problem.RELATION)
                if (t.kind == Kinds.REPAYMENT) ensure(rows.size == 1, Problem.RELATION)
            } else ensure(rows.isEmpty(), Problem.RELATION)
        }
        s.allocations.forEach { a ->
            amount(a.amount)
            val t = transfers[a.transferId] ?: throw LedgerException(Problem.RELATION)
            ensure((a.shareId == null) != (a.loanId == null), Problem.RELATION)
            if (a.shareId != null) {
                val share = shares[a.shareId] ?: throw LedgerException(Problem.RELATION)
                val bill = bills.getValue(share.expenseId)
                ensure(t.kind == Kinds.BILL && t.fromId == share.memberId && t.toId == bill.payerId && t.fromId != bill.payerId && t.date >= bill.date, Problem.RELATION)
            } else {
                val loan = transfers[a.loanId] ?: throw LedgerException(Problem.RELATION)
                ensure(t.kind == Kinds.REPAYMENT && loan.kind == Kinds.LOAN && t.fromId == loan.toId && t.toId == loan.fromId && t.date >= loan.date, Problem.RELATION)
            }
        }
        s.shares.forEach { share -> ensure(Money.sum(s.allocations.filter { it.shareId == share.id }.map { it.amount }) <= share.amount, Problem.OVERPAYMENT) }
        s.transfers.filter { it.kind == Kinds.LOAN }.forEach { loan -> ensure(Money.sum(s.allocations.filter { it.loanId == loan.id }.map { it.amount }) <= loan.amount, Problem.OVERPAYMENT) }
    }
}
