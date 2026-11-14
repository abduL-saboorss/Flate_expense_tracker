package com.houseledger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.houseledger.app.AppContainer
import com.houseledger.app.data.Ledger
import com.houseledger.app.domain.LedgerException
import com.houseledger.app.domain.Problem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LedgerViewModel(val container: AppContainer) : ViewModel() {
    val ledger = container.repository.ledger.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val theme = container.preferences.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM")
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<LedgerException?>(null)
    fun run(done: () -> Unit = {}, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { action(); done() } catch (e: Exception) { error.value = if (e is LedgerException) e else LedgerException(Problem.BACKUP) }
            finally { busy.value = false }
        }
    }
    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return LedgerViewModel(container) as T }
        }
    }
}
