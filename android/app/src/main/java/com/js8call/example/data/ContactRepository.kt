package com.js8call.example.data

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for heard-station contacts. Every complete decode passes
 * through [recordDecode], which parses out the sender, an optional grid,
 * and whether the message was directed at our callsign.
 */
class ContactRepository(context: Context) {

    private val contactDao = MessageDatabase.getInstance(context).contactDao()

    fun getContacts(): LiveData<List<ContactEntity>> = contactDao.getContacts()

    suspend fun setStarred(callsign: String, starred: Boolean) {
        withContext(Dispatchers.IO) { contactDao.setStarred(callsign, starred) }
    }

    suspend fun setComment(callsign: String, comment: String?) {
        withContext(Dispatchers.IO) { contactDao.setComment(callsign, comment) }
    }

    suspend fun deleteContact(callsign: String) {
        withContext(Dispatchers.IO) { contactDao.deleteContact(callsign) }
    }

    /**
     * Update the sender's contact entry from one complete decoded message.
     * Does nothing when the text does not start with a callsign.
     */
    suspend fun recordDecode(
        text: String,
        snr: Int,
        offsetHz: Float,
        timestamp: Long,
        myCallsign: String?
    ) {
        val trimmed = text.trim()
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.isEmpty()) return

        val sender = tokens[0].trimEnd(':').uppercase()
        if (!isCallsignLike(sender)) return

        val grid = tokens.lastOrNull()?.uppercase()
            ?.takeIf { it != "RR73" && gridRegex.matches(it) }

        withContext(Dispatchers.IO) {
            val inserted = contactDao.insertIgnore(
                ContactEntity(
                    callsign = sender,
                    lastHeard = timestamp,
                    snr = snr,
                    offset = offsetHz,
                    grid = grid
                )
            )
            if (inserted == -1L) {
                contactDao.updateHeard(sender, timestamp, snr, offsetHz, grid)
            }

            // "SENDER: MYCALL ..." means the station copied us.
            if (!myCallsign.isNullOrBlank() &&
                tokens[0].endsWith(":") &&
                tokens.getOrNull(1)?.uppercase() == myCallsign.uppercase()
            ) {
                contactDao.markHeardUs(sender)
            }
        }
    }

    private fun isCallsignLike(token: String): Boolean {
        if (token.length !in 3..12) return false
        if (!callsignRegex.matches(token)) return false
        return token.any { it.isLetter() } && token.any { it.isDigit() }
    }

    companion object {
        private val callsignRegex = Regex("^[A-Z0-9/]+$")
        private val gridRegex = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")

        @Volatile
        private var INSTANCE: ContactRepository? = null

        fun getInstance(context: Context): ContactRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContactRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
