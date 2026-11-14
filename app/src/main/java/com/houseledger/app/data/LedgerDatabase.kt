package com.houseledger.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM households") fun observeHouseholds(): Flow<List<Household>>
    @Query("SELECT * FROM members") fun observeMembers(): Flow<List<Member>>
    @Query("SELECT * FROM categories") fun observeCategories(): Flow<List<Category>>
    @Query("SELECT * FROM personal_transactions") fun observePersonal(): Flow<List<PersonalTransaction>>
    @Query("SELECT * FROM household_expenses") fun observeExpenses(): Flow<List<HouseholdExpense>>
    @Query("SELECT * FROM expense_shares") fun observeShares(): Flow<List<ExpenseShare>>
    @Query("SELECT * FROM transfers") fun observeTransfers(): Flow<List<Transfer>>
    @Query("SELECT * FROM payment_allocations") fun observeAllocations(): Flow<List<PaymentAllocation>>
    @Query("SELECT * FROM households") suspend fun households(): List<Household>
    @Query("SELECT * FROM members") suspend fun members(): List<Member>
    @Query("SELECT * FROM categories") suspend fun categories(): List<Category>
    @Query("SELECT * FROM personal_transactions") suspend fun personal(): List<PersonalTransaction>
    @Query("SELECT * FROM household_expenses") suspend fun expenses(): List<HouseholdExpense>
    @Query("SELECT * FROM expense_shares") suspend fun shares(): List<ExpenseShare>
    @Query("SELECT * FROM transfers") suspend fun transfers(): List<Transfer>
    @Query("SELECT * FROM payment_allocations") suspend fun allocations(): List<PaymentAllocation>
    @Insert suspend fun insertHouseholds(rows: List<Household>)
    @Insert suspend fun insertMembers(rows: List<Member>)
    @Insert suspend fun insertCategories(rows: List<Category>)
    @Insert suspend fun insertPersonal(rows: List<PersonalTransaction>)
    @Insert suspend fun insertExpenses(rows: List<HouseholdExpense>)
    @Insert suspend fun insertShares(rows: List<ExpenseShare>)
    @Insert suspend fun insertTransfers(rows: List<Transfer>)
    @Insert suspend fun insertAllocations(rows: List<PaymentAllocation>)
    @Query("DELETE FROM payment_allocations") suspend fun clearAllocations()
    @Query("DELETE FROM transfers") suspend fun clearTransfers()
    @Query("DELETE FROM expense_shares") suspend fun clearShares()
    @Query("DELETE FROM household_expenses") suspend fun clearExpenses()
    @Query("DELETE FROM personal_transactions") suspend fun clearPersonal()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM members") suspend fun clearMembers()
    @Query("DELETE FROM households") suspend fun clearHouseholds()
    @Transaction suspend fun read(): Ledger = Ledger(households(), members(), categories(), personal(), expenses(), shares(), transfers(), allocations())
    // One atomic replacement keeps all eight tables consistent for mutations and imports.
    @Transaction suspend fun replace(s: Ledger) {
        clearAllocations(); clearTransfers(); clearShares(); clearExpenses(); clearPersonal(); clearCategories(); clearMembers(); clearHouseholds()
        insertHouseholds(s.households); insertMembers(s.members); insertCategories(s.categories)
        insertPersonal(s.personal); insertExpenses(s.expenses); insertShares(s.shares)
        insertTransfers(s.transfers); insertAllocations(s.allocations)
    }
}

@Database(entities = [Household::class, Member::class, Category::class, PersonalTransaction::class,
    HouseholdExpense::class, ExpenseShare::class, Transfer::class, PaymentAllocation::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE households ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
