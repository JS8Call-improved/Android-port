package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.js8call.example.data.ContactEntity
import com.js8call.example.data.ContactRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for the Contacts screen: stations heard on the air,
 * starred first, then most recent.
 */
class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ContactRepository.getInstance(application)

    val contacts: LiveData<List<ContactEntity>> = repository.getContacts()

    fun setStarred(callsign: String, starred: Boolean) {
        viewModelScope.launch { repository.setStarred(callsign, starred) }
    }

    fun setComment(callsign: String, comment: String?) {
        viewModelScope.launch { repository.setComment(callsign, comment) }
    }

    fun deleteContact(callsign: String) {
        viewModelScope.launch { repository.deleteContact(callsign) }
    }
}
