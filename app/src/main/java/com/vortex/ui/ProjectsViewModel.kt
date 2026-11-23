package com.vortex.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.data.AppDatabase
import com.vortex.data.ProjectEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).projects()

    val projects = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        dao.insert(ProjectEntity(name = name, createdAt = now, lastOpenedAt = now))
    }
    fun rename(id: Long, name: String) = viewModelScope.launch { dao.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch {
        dao.delete(id); dao.deleteValues(id); dao.deleteMessages(id); dao.deleteVocals(id)
    }
    fun touch(id: Long) = viewModelScope.launch { dao.touch(id, System.currentTimeMillis()) }
}
