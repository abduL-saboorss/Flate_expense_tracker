package com.houseledger.app.data

import androidx.room.*
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

@Serializable
@Entity(tableName = "households")
data class Household(@PrimaryKey val id: String = newId(), val name: String,
    val myMemberId: String, val currency: String = "PKR", val createdAt: Long = now(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = now())

@Serializable
@Entity(tableName = "members", foreignKeys = [ForeignKey(Household::class, ["id"], ["householdId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("householdId")])
data class Member(@PrimaryKey val id: String = newId(), val householdId: String, val name: String,
    val note: String = "", val archived: Boolean = false, val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
@Entity(tableName = "categories", foreignKeys = [ForeignKey(Household::class, ["id"], ["householdId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("householdId")])
data class Category(@PrimaryKey val id: String = newId(), val householdId: String, val name: String,
    val scope: String, val archived: Boolean = false, val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
@Entity(tableName = "personal_transactions", foreignKeys = [ForeignKey(Category::class, ["id"], ["categoryId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("date"), Index("categoryId")])
data class PersonalTransaction(@PrimaryKey val id: String = newId(), val type: String, val amount: Long,
    val date: String, val categoryId: String, val title: String, val note: String = "", val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
@Entity(tableName = "household_expenses", foreignKeys = [ForeignKey(Member::class, ["id"], ["payerId"], onDelete = ForeignKey.RESTRICT), ForeignKey(Category::class, ["id"], ["categoryId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("date"), Index("payerId"), Index("categoryId")])
data class HouseholdExpense(@PrimaryKey val id: String = newId(), val amount: Long, val date: String,
    val categoryId: String, val title: String, val payerId: String, val splitMethod: String,
    val note: String = "", val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
@Entity(tableName = "expense_shares", foreignKeys = [ForeignKey(HouseholdExpense::class, ["id"], ["expenseId"], onDelete = ForeignKey.RESTRICT), ForeignKey(Member::class, ["id"], ["memberId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("expenseId"), Index("memberId"), Index(value = ["expenseId", "memberId"], unique = true)])
data class ExpenseShare(@PrimaryKey val id: String = newId(), val expenseId: String, val memberId: String,
    val amount: Long, val basisPoints: Int? = null, val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
@Entity(tableName = "transfers", foreignKeys = [ForeignKey(Member::class, ["id"], ["fromId"], onDelete = ForeignKey.RESTRICT), ForeignKey(Member::class, ["id"], ["toId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("date"), Index("fromId"), Index("toId")])
data class Transfer(@PrimaryKey val id: String = newId(), val kind: String, val fromId: String, val toId: String,
    val amount: Long, val date: String, val note: String = "", val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
@Entity(tableName = "payment_allocations", foreignKeys = [ForeignKey(Transfer::class, ["id"], ["transferId"], onDelete = ForeignKey.RESTRICT), ForeignKey(ExpenseShare::class, ["id"], ["shareId"], onDelete = ForeignKey.RESTRICT), ForeignKey(Transfer::class, ["id"], ["loanId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("transferId"), Index("shareId"), Index("loanId")])
data class PaymentAllocation(@PrimaryKey val id: String = newId(), val transferId: String,
    val shareId: String? = null, val loanId: String? = null, val amount: Long,
    val createdAt: Long = now(), val updatedAt: Long = now())

@Serializable
data class Ledger(val households: List<Household> = emptyList(), val members: List<Member> = emptyList(),
    val categories: List<Category> = emptyList(), val personal: List<PersonalTransaction> = emptyList(),
    val expenses: List<HouseholdExpense> = emptyList(), val shares: List<ExpenseShare> = emptyList(),
    val transfers: List<Transfer> = emptyList(), val allocations: List<PaymentAllocation> = emptyList()) {
    val household: Household? get() = households.singleOrNull()
    fun member(id: String): String = members.find { it.id == id }?.name.orEmpty()
    fun category(id: String): String = categories.find { it.id == id }?.name.orEmpty()
}

object Kinds {
    const val INCOME = "INCOME"
    const val EXPENSE = "EXPENSE"
    const val BILL = "BILL_SETTLEMENT"
    const val LOAN = "LOAN"
    const val REPAYMENT = "LOAN_REPAYMENT"
    const val OTHER = "OTHER"
    const val EQUAL = "EQUAL"
    const val EXACT = "EXACT"
    const val PERCENT = "PERCENT"
}
