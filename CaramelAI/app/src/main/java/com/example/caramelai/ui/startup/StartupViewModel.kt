package com.example.caramelai.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.domain.ConnectionChecker
import com.example.caramelai.domain.ConnectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StartupUiState {
    data object Checking : StartupUiState
    data object Connected : StartupUiState
    data class Failed(val message: String) : StartupUiState
}

class StartupViewModel(
    private val connectionChecker: ConnectionChecker,
) : ViewModel() {
    private val _uiState = MutableStateFlow<StartupUiState>(StartupUiState.Checking)
    val uiState: StateFlow<StartupUiState> = _uiState.asStateFlow()

    init {
        check()
    }

    fun check() {
        viewModelScope.launch {
            _uiState.value = StartupUiState.Checking
            when (val result = connectionChecker.check()) {
                is ConnectionResult.Ok -> _uiState.value = StartupUiState.Connected
                is ConnectionResult.Failed -> _uiState.value = StartupUiState.Failed(result.message)
            }
        }
    }
}
