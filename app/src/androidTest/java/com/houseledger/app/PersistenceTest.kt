package com.houseledger.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.houseledger.app.data.*
import com.houseledger.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    private fun database(name: String) = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_1_2).build()
    @Test fun migrationPreservesProfile() {
        migration.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO households (id,name,myMemberId,currency,createdAt) VALUES ('h','Home','me','PKR',1)")
            execSQL("INSERT INTO members (id,householdId,name,note,archived,createdAt,updatedAt) VALUES ('me','h','Me','',0,1,1)")
            close()
        }
        migration.runMigrationsAndValidate("migration-test", 2, true, LedgerDatabase.MIGRATION_1_2).apply {
            query("SELECT name, updatedAt FROM households").use { assertTrue(it.moveToFirst()); assertEquals("Home", it.getString(0)); assertEquals(0L, it.getLong(1)) }; close()
        }
    }
    @Test fun restartAtomicRestoreAndLinkedCrud() = runBlocking {
        val name = "persistence-test"; context.deleteDatabase(name)
        var db = database(name); var repo = LedgerRepository(db)
        repo.setup("Me", "Home", listOf("Ali"), mapOf("HOUSEHOLD" to listOf("Electricity"), "INCOME" to listOf("Salary")))
        val initial = repo.snapshot(); val me = initial.household!!.myMemberId; val ali = initial.members.first { it.id != me }.id
        val b = HouseholdExpense("b", 600000, "2026-11-01", initial.categories.first { it.scope == "HOUSEHOLD" }.id, "Electricity", me, Kinds.EQUAL)
        repo.saveExpense(b, listOf(ExpenseShare("s1", "b", me, 300000), ExpenseShare("s2", "b", ali, 300000)))
        val t = Transfer("t", Kinds.BILL, ali, me, 80000, "2026-11-02")
        repo.saveTransfer(t, null); val paid = repo.snapshot()
        assertEquals(220000L, Accounting.outstanding(paid, "2026-11-30").single().remaining)
        assertTrue(runCatching { repo.saveExpense(b.copy(amount = 700000), paid.shares) }.isFailure)
        assertTrue(runCatching { repo.delete("expense", "b") }.isFailure)
        repo.saveExpense(b.copy(title = "Updated electricity"), paid.shares)
        db.close(); db = database(name); repo = LedgerRepository(db)
        assertEquals("Updated electricity", repo.snapshot().expenses.single().title)
        val backup = Backups.decode(Backups.encode(Backup(ledger = repo.snapshot())))
        val before = repo.snapshot()
        assertTrue(runCatching { repo.restore(backup.copy(ledger = backup.ledger.copy(shares = emptyList()))) }.isFailure)
        assertEquals(before, repo.snapshot())
        repo.delete("transfer", "t"); assertEquals(300000L, Accounting.outstanding(repo.snapshot(), "2026-11-30").single().remaining)
        repo.saveMember(repo.snapshot().members.first { it.id == ali }.copy(archived = true))
        assertEquals(1, Accounting.outstanding(repo.snapshot(), "2026-11-30").size)
        repo.restore(backup); assertEquals(before, repo.snapshot()); db.close()
        val clean = database("clean-restore-test"); LedgerRepository(clean).restore(backup); assertEquals(before, LedgerRepository(clean).snapshot()); clean.close()
    }
    @Test fun failedRoomWriteRollsBackAllTables() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build(); val repo = LedgerRepository(db)
        repo.setup("Me", "Home", emptyList(), mapOf("INCOME" to listOf("Salary")))
        val before = repo.snapshot()
        val invalid = before.copy(personal = listOf(PersonalTransaction("bad", Kinds.INCOME, 100, "2026-11-01", "missing", "Broken")))
        assertTrue(runCatching { db.dao().replace(invalid) }.isFailure)
        assertEquals(before, repo.snapshot()); db.close()
    }
    @Test fun sharePrivacyAndFigures() {
        val s = Ledger(households = listOf(Household("h", "Home", "me")), members = listOf(Member("me", "h", "Me"), Member("a", "h", "Ali")), categories = listOf(Category("c", "h", "Electricity", "HOUSEHOLD")),
            expenses = listOf(HouseholdExpense("b", 600000, "2026-11-01", "c", "Electricity", "me", Kinds.EQUAL, "PRIVATE BILL NOTE")), shares = listOf(ExpenseShare("s1", "b", "me", 300000), ExpenseShare("s2", "b", "a", 300000)),
            transfers = listOf(Transfer("l", Kinds.LOAN, "me", "a", 10000, "2026-11-01", "PRIVATE LOAN NOTE")))
        val messages = com.houseledger.app.ui.Messages(context)
        assertTrue(messages.bill(s, "b").contains("6,000.00")); assertFalse(messages.bill(s, "b").contains("PRIVATE"))
        assertFalse(messages.month(s, java.time.YearMonth.of(2026, 11)).contains("PRIVATE"))
        assertFalse(messages.member(s, "a", "2026-11-30").contains("PRIVATE"))
    }
    @Test fun personalLoanTransferMemberAndCategoryCrud() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build(); val repo = LedgerRepository(db)
        repo.setup("Me", "Home", listOf("Ali"), mapOf("INCOME" to listOf("Salary")))
        var s = repo.snapshot(); val me = s.household!!.myMemberId; val ali = s.members.first { it.id != me }.id
        val category = Category("custom", s.household!!.id, "Bonus", Kinds.INCOME)
        repo.saveCategory(category); repo.saveCategory(category.copy(name = "Bonuses"))
        val personal = PersonalTransaction("p", Kinds.INCOME, 10000, "2026-11-01", category.id, "Bonus")
        repo.savePersonal(personal); repo.savePersonal(personal.copy(amount = 12000))
        assertTrue(runCatching { repo.delete("category", category.id) }.isFailure)
        repo.delete("personal", "p"); repo.delete("category", category.id)
        val loan = Transfer("loan", Kinds.LOAN, me, ali, 10000, "2026-11-01")
        repo.saveTransfer(loan, null); val repayment = Transfer("repay", Kinds.REPAYMENT, ali, me, 4000, "2026-11-02")
        repo.saveTransfer(repayment, "loan"); repo.saveTransfer(repayment.copy(amount = 3000), "loan")
        assertEquals(7000L, Accounting.outstanding(repo.snapshot(), "2026-11-30").single().remaining)
        assertTrue(runCatching { repo.saveTransfer(loan.copy(amount = 5000), null) }.isFailure)
        assertTrue(runCatching { repo.delete("transfer", "loan") }.isFailure)
        repo.saveTransfer(loan.copy(note = "A harmless note edit"), null)
        repo.delete("transfer", "repay"); repo.delete("transfer", "loan")
        val other = Transfer("other", Kinds.OTHER, me, ali, 500, "2026-11-01")
        repo.saveTransfer(other, null); repo.saveTransfer(other.copy(amount = 600), null); repo.delete("transfer", "other")
        val member = Member("unused", s.household!!.id, "New member")
        repo.saveMember(member); repo.saveMember(member.copy(archived = true)); repo.saveMember(member.copy(name = "Renamed", archived = false)); repo.delete("member", "unused")
        s = repo.snapshot(); assertTrue(s.transfers.isEmpty()); assertTrue(s.personal.isEmpty()); assertEquals(2, s.members.size)
        db.close()
    }
}
