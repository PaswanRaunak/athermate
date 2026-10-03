package io.ather.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ather.pro.domain.update.AppUpdateRepository
import kotlinx.coroutines.launch

class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {
    val state = repository.state
    fun check(force: Boolean = true) { viewModelScope.launch { repository.check(force) } }
    fun download() { viewModelScope.launch { repository.download() } }
    class Factory(private val repository: AppUpdateRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppUpdateViewModel(repository) as T
    }
}
