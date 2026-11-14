package com.houseledger.app

import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class AccountingTest {
    private fun fixture(): Ledger {
        val h = Household("h", "Home", "me")
        return Ledger(listOf(h), listOf("me", "ali", "sara", "excluded").map { Member(it, h.id, it) }, listOf(Category("c", "h", "Electricity", "HOUSEHOLD"), Category("i", "h", "Salary", "INCOME"), Category("p", "h", "Food", "EXPENSE")))
    }
    private fun bill(): Ledger {
        val bill = HouseholdExpense("b", 600000, "2026-11-01", "c", "Electricity", "me", Kinds.EQUAL)
        return fixture().copy(expenses = listOf(bill), shares = listOf("me", "ali", "sara").map { ExpenseShare(it, "b", it, 200000) })
    }
    private fun pay(s: Ledger, amount: Long = 80000, date: String = "2026-12-01"): Ledger {
        val t = Transfer("payment", Kinds.BILL, "ali", "me", amount, date)
        return s.copy(transfers = s.transfers + t, allocations = s.allocations + Accounting.allocations(s, t))
    }
    @Test fun decimalParsingIsExact() { assertEquals(10001L, Money.parse("100.01")); assertEquals(10L, Money.parse("0.1")) }
    @Test fun rejectsExtraDecimalsAndExponent() { listOf("1.001", "1e2", "NaN", "-1", "0", "100,000").forEach { assertTrue(it, runCatching { Money.parse(it) }.isFailure) } }
    @Test fun equalRemainderStable() { assertEquals(listOf(3334L, 3333L, 3333L), Splits.equal(10000, listOf("a", "b", "c")).values.toList()) }
    @Test fun percentagesUseIntegerRemainders() { assertEquals(listOf(3334L, 3333L, 3333L), Splits.percentages(10000, linkedMapOf("a" to 3334, "b" to 3333, "c" to 3333)).values.toList()) }
    @Test fun percentagesHandleMaximumWithoutOverflow() { val result = Splits.percentages(Money.MAX, linkedMapOf("a" to 5000, "b" to 5000)); assertEquals(Money.MAX, Money.sum(result.values)) }
    @Test fun zeroPercentGetsNoRemainder() { assertEquals(0L, Splits.percentages(1, linkedMapOf("a" to 0, "b" to 10000))["a"]) }
    @Test fun invalidSplitsRejected() { assertTrue(runCatching { Splits.exact(100, mapOf("a" to 99)) }.isFailure); assertTrue(runCatching { Splits.percentages(100, mapOf("a" to 9999)) }.isFailure) }
    @Test fun householdCostAndCashAreSeparate() {
        val s = bill(); LedgerValidator.validate(s)
        val t = Accounting.totals(s, YearMonth.of(2026, 11))
        assertEquals(600000L, t.cashOut); assertEquals(200000L, t.myHouseholdCost); assertEquals(0L, t.personalExpense)
        val o = Accounting.outstanding(s, "2026-11-30"); assertEquals(2, o.size); assertFalse(o.any { it.debtor == "excluded" })
    }
    @Test fun payerOutsideParticipants() {
        val s = bill().copy(expenses = listOf(bill().expenses.single().copy(amount = 400000)), shares = bill().shares.filter { it.memberId != "me" })
        LedgerValidator.validate(s); assertEquals(0L, Accounting.totals(s, YearMonth.of(2026, 11)).myHouseholdCost)
    }
    @Test fun partialPaymentAndDeletion() {
        val original = bill(); val s = pay(original); LedgerValidator.validate(s)
        assertEquals(120000L, Accounting.outstanding(s, "2026-12-31").first { it.debtor == "ali" }.remaining)
        assertEquals(80000L, Accounting.totals(s, YearMonth.of(2026, 12)).cashIn)
        assertEquals(200000L, Accounting.outstanding(original, "2026-12-31").first { it.debtor == "ali" }.remaining)
    }
    @Test fun laterPaymentsDoNotChangeEarlierMonthEnd() {
        val s = pay(bill(), date = "2027-01-01")
        assertEquals(200000L, Accounting.outstanding(s, "2026-12-31").first { it.debtor == "ali" }.remaining)
        assertEquals(0L, Accounting.totals(s, YearMonth.of(2026, 12)).householdSpending)
    }
    @Test fun overpaymentRejected() { assertTrue(runCatching { pay(bill(), 200001) }.isFailure) }
    @Test fun oldestBillAllocation() {
        val s = bill(); val second = s.expenses.single().copy(id = "b2", date = "2026-12-01")
        val next = s.copy(expenses = s.expenses + second, shares = s.shares + s.shares.map { it.copy(id = "new-" + it.id, expenseId = "b2") })
        val t = Transfer("t", Kinds.BILL, "ali", "me", 250000, "2026-12-02")
        val rows = Accounting.allocations(next, t); assertEquals("ali", rows.first().shareId); assertEquals(listOf(200000L, 50000L), rows.map { it.amount })
    }
    @Test fun loanAndRepaymentSeparateFromBills() {
        val loan = Transfer("l", Kinds.LOAN, "me", "ali", 100000, "2026-11-03", "Loan")
        val s = bill().copy(transfers = listOf(loan)); val payment = Transfer("r", Kinds.REPAYMENT, "ali", "me", 40000, "2026-11-04")
        val next = s.copy(transfers = s.transfers + payment, allocations = Accounting.allocations(s, payment, "l")); LedgerValidator.validate(next)
        assertEquals(60000L, Accounting.outstanding(next, "2026-11-30").first { it.loan }.remaining)
        assertEquals(200000L, Accounting.outstanding(next, "2026-11-30").first { it.debtor == "ali" && !it.loan }.remaining)
        assertEquals(-660000L, Accounting.totals(next, YearMonth.of(2026, 11)).netCash)
        assertTrue(runCatching { Accounting.allocations(s, payment) }.isFailure)
    }
    @Test fun otherTransferDoesNotChangeDebts() {
        val s = bill(); val next = s.copy(transfers = listOf(Transfer("o", Kinds.OTHER, "ali", "me", 5000, "2026-11-04")))
        LedgerValidator.validate(next); assertEquals(Accounting.outstanding(s, "2026-11-30"), Accounting.outstanding(next, "2026-11-30"))
    }
    @Test fun transfersBetweenOthersDoNotAffectMyCash() { val s = fixture().copy(transfers = listOf(Transfer("o", Kinds.OTHER, "ali", "sara", 5000, "2026-11-04"))); assertEquals(0L, Accounting.totals(s, YearMonth.of(2026, 11)).netCash) }
    @Test fun archivedMembersKeepObligations() { val s = bill(); val next = s.copy(members = s.members.map { if (it.id == "ali") it.copy(archived = true) else it }); LedgerValidator.validate(next); assertEquals(Accounting.outstanding(s, "2026-11-30"), Accounting.outstanding(next, "2026-11-30")) }
    @Test fun backupRoundTrip() { val s = pay(bill()); val backup = Backup(ledger = s, theme = "DARK"); assertEquals(backup, Backups.decode(Backups.encode(backup))) }
    @Test fun backupRequiresSavedIdsAndAuditTimes() {
        val json = Backups.encode(Backup(ledger = fixture()))
        assertTrue(runCatching { Backups.decode(json.replace("\"id\": \"c\",", "")) }.isFailure)
        assertTrue(runCatching { LedgerValidator.validate(fixture().copy(members = fixture().members.map { it.copy(createdAt = -1) })) }.isFailure)
    }
    @Test fun backupRejectsInvalidReferencesAndVersion() {
        val s = bill(); assertTrue(runCatching { Backups.decode(Backups.encode(Backup(ledger = s.copy(members = s.members.filterNot { it.id == "ali" })))) }.isFailure)
        assertTrue(runCatching { Backups.decode(Backups.encode(Backup(schemaVersion = 99, ledger = s))) }.isFailure)
    }
    @Test fun corruptAndOverallocatedBackupsRejected() {
        assertTrue(runCatching { Backups.decode("{broken") }.isFailure)
        val s = pay(bill()); val bad = s.copy(allocations = s.allocations.map { it.copy(amount = 300000) }, transfers = s.transfers.map { it.copy(amount = 300000) })
        assertTrue(runCatching { LedgerValidator.validate(bad) }.isFailure)
    }
    @Test fun paymentBeforeBillRejected() { val s = pay(bill()); val bad = s.copy(transfers = s.transfers.map { it.copy(date = "2026-10-31") }); assertTrue(runCatching { LedgerValidator.validate(bad) }.isFailure) }
}
