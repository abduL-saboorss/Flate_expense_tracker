package com.houseledger.app.data

import androidx.room.withTransaction
import com.houseledger.app.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

@Serializable data class Backup(val schemaVersion: Int = 1, val exportedAt: Long = now(), val theme: String = "SYSTEM", val ledger: Ledger)
object Backups {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(backup: Backup): String = json.encodeToString(backup)
    fun decode(text: String): Backup {
        ensure(text.length <= 20_000_000, Problem.BACKUP)
        ensure(runCatching { json.parseToJsonElement(text).jsonObject.containsKey("schemaVersion") }.getOrDefault(false), Problem.VERSION)
        // Entity constructors generate IDs for new records. An import must supply its saved IDs.
        ensure(runCatching {
            val raw = json.parseToJsonElement(text).jsonObject.getValue("ledger").jsonObject
            listOf("households", "members", "categories", "personal", "expenses", "shares", "transfers", "allocations").all { table ->
                raw.getValue(table).jsonArray.all { row -> row.jsonObject.keys.containsAll(listOf("id", "createdAt", "updatedAt")) }
            }
        }.getOrDefault(false), Problem.BACKUP)
        val backup = try { json.decodeFromString<Backup>(text) } catch (_: Exception) { throw LedgerException(Problem.BACKUP) }
        ensure(backup.schemaVersion == 1, Problem.VERSION)
        ensure(backup.theme in listOf("SYSTEM", "LIGHT", "DARK"), Problem.BACKUP)
        LedgerValidator.validate(backup.ledger)
        return backup
    }
}

class LedgerRepository(val db: LedgerDatabase) {
    val ledger = db.invalidationTracker.createFlow("households", "members", "categories", "personal_transactions", "household_expenses", "expense_shares", "transfers", "payment_allocations").map { db.withTransaction { db.dao().read() } }
    suspend fun snapshot(): Ledger = db.withTransaction { db.dao().read() }
    private suspend fun mutate(block: (Ledger) -> Ledger) = db.withTransaction {
        val next = block(db.dao().read())
        LedgerValidator.validate(next)
        db.dao().replace(next)
    }
    suspend fun setup(myName: String, householdName: String, memberNames: List<String>, categories: Map<String, List<String>>) = mutate {
        ensure(it.households.isEmpty(), Problem.RELATION)
        val me = newId(); val home = Household(name = householdName.trim(), myMemberId = me)
        Ledger(listOf(home), listOf(Member(id = me, householdId = home.id, name = myName.trim())) + memberNames.filter { n -> n.isNotBlank() }.map { n -> Member(householdId = home.id, name = n.trim()) },
            categories.flatMap { (scope, names) -> names.map { n -> Category(householdId = home.id, name = n, scope = scope) } })
    }
    suspend fun savePersonal(row: PersonalTransaction) = mutate { it.copy(personal = it.personal.filterNot { p -> p.id == row.id } + row) }
    suspend fun saveExpense(row: HouseholdExpense, shares: List<ExpenseShare>) = mutate { s ->
        val old = s.expenses.find { it.id == row.id }
        val oldShares = s.shares.filter { it.expenseId == row.id }
        val linked = s.allocations.filter { a -> oldShares.any { it.id == a.shareId } }
        if (old != null && linked.isNotEmpty()) {
            val unchanged = old.amount == row.amount && old.payerId == row.payerId && old.date == row.date && old.splitMethod == row.splitMethod && oldShares.map { Triple(it.id, it.amount, it.basisPoints) }.toSet() == shares.map { Triple(it.id, it.amount, it.basisPoints) }.toSet()
            ensure(unchanged, Problem.LINKED, linked.joinToString { a -> s.transfers.first { it.id == a.transferId }.date + " · " + Money.format(a.amount) })
        }
        s.copy(expenses = s.expenses.filterNot { it.id == row.id } + row, shares = s.shares.filterNot { it.expenseId == row.id } + shares)
    }
    suspend fun saveTransfer(row: Transfer, selectedId: String?) = mutate { s ->
        val old = s.transfers.find { it.id == row.id }
        val linked = s.allocations.filter { it.loanId == row.id }
        if (old != null && linked.isNotEmpty()) ensure(old.amount == row.amount && old.fromId == row.fromId && old.toId == row.toId && old.date == row.date && old.kind == row.kind, Problem.LINKED, linked.joinToString { Money.format(it.amount) })
        val clean = s.copy(transfers = s.transfers.filterNot { it.id == row.id }, allocations = s.allocations.filterNot { it.transferId == row.id })
        val previous = s.allocations.filter { it.transferId == row.id }
        val sameFinancials = old != null && old.amount == row.amount && old.fromId == row.fromId && old.toId == row.toId && old.date == row.date && old.kind == row.kind
        val sameTarget = selectedId == null || previous.singleOrNull()?.let { (it.shareId ?: it.loanId) == selectedId } == true
        val allocations = if (sameFinancials && sameTarget) previous else if (row.kind in listOf(Kinds.BILL, Kinds.REPAYMENT)) Accounting.allocations(clean, row, selectedId) else emptyList()
        clean.copy(transfers = clean.transfers + row, allocations = clean.allocations + allocations)
    }
    suspend fun saveMember(row: Member) = mutate { it.copy(members = it.members.filterNot { m -> m.id == row.id } + row) }
    suspend fun saveCategory(row: Category) = mutate { it.copy(categories = it.categories.filterNot { c -> c.id == row.id } + row) }
    suspend fun saveProfile(name: String, myName: String) = mutate { s ->
        val h = s.household!!
        s.copy(households = listOf(h.copy(name = name.trim(), updatedAt = now())), members = s.members.map { if (it.id == h.myMemberId) it.copy(name = myName.trim(), updatedAt = now()) else it })
    }
    suspend fun delete(type: String, id: String) = mutate { s ->
        when (type) {
            "personal" -> s.copy(personal = s.personal.filterNot { it.id == id })
            "expense" -> {
                val shareIds = s.shares.filter { it.expenseId == id }.map { it.id }
                val linked = s.allocations.filter { it.shareId in shareIds }
                ensure(linked.isEmpty(), Problem.LINKED, linked.joinToString { a -> s.transfers.first { it.id == a.transferId }.date + " · " + Money.format(a.amount) })
                s.copy(expenses = s.expenses.filterNot { it.id == id }, shares = s.shares.filterNot { it.expenseId == id })
            }
            "transfer" -> { ensure(s.allocations.none { it.loanId == id }, Problem.LINKED); s.copy(transfers = s.transfers.filterNot { it.id == id }, allocations = s.allocations.filterNot { it.transferId == id }) }
            "member" -> {
                ensure(s.household?.myMemberId != id, Problem.SELF)
                ensure(s.expenses.none { it.payerId == id } && s.shares.none { it.memberId == id } && s.transfers.none { it.fromId == id || it.toId == id }, Problem.REFERENCED)
                s.copy(members = s.members.filterNot { it.id == id })
            }
            "category" -> { ensure(s.personal.none { it.categoryId == id } && s.expenses.none { it.categoryId == id }, Problem.REFERENCED); s.copy(categories = s.categories.filterNot { it.id == id }) }
            else -> throw LedgerException(Problem.RELATION)
        }
    }
    suspend fun restore(backup: Backup) = db.withTransaction { LedgerValidator.validate(backup.ledger); db.dao().replace(backup.ledger) }
}
