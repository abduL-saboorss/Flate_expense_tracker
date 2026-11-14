package com.houseledger.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.houseledger.app.data.*
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("display")
class DisplayPreferences(private val context: Context) {
    private val key = stringPreferencesKey("theme")
    val theme = context.preferences.data.map { it[key] ?: "SYSTEM" }
    suspend fun theme(value: String) { context.preferences.edit { it[key] = value } }
}
class AppContainer(context: Context) {
    val db = Room.databaseBuilder(context, LedgerDatabase::class.java, "house-ledger.db").addMigrations(LedgerDatabase.MIGRATION_1_2).build()
    val repository = LedgerRepository(db)
    val preferences = DisplayPreferences(context)
}
class LedgerApplication : Application() { val container by lazy { AppContainer(this) } }
