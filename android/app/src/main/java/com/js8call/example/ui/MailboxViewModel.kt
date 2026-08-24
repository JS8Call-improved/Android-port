package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import com.js8call.example.data.MailboxEntity
import com.js8call.example.data.MailboxRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for the Held messages screen: mail this station holds for
 * other operators, grouped by destination.
 */
class MailboxViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MailboxRepository(application)

    /** Held count for the badge on the Messages header. */
    val heldCount: LiveData<Int> = repository.getHeldCount()

    /**
     * All mailbox rows joined with per-message collection counts, ready
     * for display. Recomputed when either source changes.
     */
    val messages: LiveData<List<MailboxRow>> = MediatorLiveData<List<MailboxRow>>().apply {
        val all = repository.getAll()
        val counts = repository.getDeliveryCounts()
        fun combine() {
            val countById = counts.value.orEmpty().associate { it.msgId to it.count }
            value = all.value.orEmpty().map { entity ->
                MailboxRow(entity, countById[entity.id] ?: 0)
            }
        }
        addSource(all) { combine() }
        addSource(counts) { combine() }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun deleteDelivered() {
        viewModelScope.launch { repository.deleteDelivered() }
    }

    data class MailboxRow(val message: MailboxEntity, val collectedBy: Int)
}
