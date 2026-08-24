package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.js8call.example.data.ContactEntity
import com.js8call.example.data.ContactRepository
import com.js8call.example.data.MailboxRepository
import com.js8call.example.util.ContactSearch
import kotlinx.coroutines.launch

/**
 * ViewModel for the Contacts screen: stations heard on the air,
 * starred first, then most recent.
 */
class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ContactRepository.getInstance(application)
    private val mailboxRepository = MailboxRepository(application)

    val contacts: LiveData<List<ContactEntity>> = repository.getContacts()

    private val _query = MutableLiveData("")
    val query: LiveData<String> = _query

    /** The list the contacts screen shows: everything, or what matches. */
    val visibleContacts: LiveData<List<ContactEntity>> =
        MediatorLiveData<List<ContactEntity>>().apply {
            fun refresh() {
                value = ContactSearch.filter(contacts.value.orEmpty(), _query.value.orEmpty())
            }
            addSource(contacts) { refresh() }
            addSource(_query) { refresh() }
        }

    fun setQuery(text: String) {
        if (_query.value == text) return
        _query.value = text
    }

    fun getContact(callsign: String): LiveData<ContactEntity?> =
        repository.getContactLive(callsign)

    fun getHeldMailCount(callsign: String): LiveData<Int> =
        mailboxRepository.getHeldCountFor(callsign)

    fun setStarred(callsign: String, starred: Boolean) {
        viewModelScope.launch { repository.setStarred(callsign, starred) }
    }

    fun setComment(callsign: String, comment: String?) {
        viewModelScope.launch { repository.setComment(callsign, comment) }
    }

    fun setName(callsign: String, name: String?) {
        viewModelScope.launch { repository.setName(callsign, name) }
    }

    fun deleteContact(callsign: String) {
        viewModelScope.launch { repository.deleteContact(callsign) }
    }
}
