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

    fun getContactLive(callsign: String): LiveData<ContactEntity?> =
        contactDao.getContactLive(callsign.trim().uppercase())

    suspend fun setStarred(callsign: String, starred: Boolean) {
        withContext(Dispatchers.IO) {
            ensureRow(callsign)
            contactDao.setStarred(callsign, starred)
        }
    }

    suspend fun setComment(callsign: String, comment: String?) {
        withContext(Dispatchers.IO) {
            ensureRow(callsign)
            contactDao.setComment(callsign, comment)
        }
    }

    suspend fun setName(callsign: String, name: String?) {
        withContext(Dispatchers.IO) {
            ensureRow(callsign)
            contactDao.setName(callsign, name)
        }
    }

    /**
     * Rows are normally written by [recordDecode], so a station named from a
     * thread the app restored but never heard has no row to update yet.
     * lastHeard of 0 marks it as never actually heard on the air.
     */
    private suspend fun ensureRow(callsign: String) {
        contactDao.insertIgnore(ContactEntity(callsign = callsign, lastHeard = 0L))
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
        val info = parseInfoReply(tokens)

        withContext(Dispatchers.IO) {
            val inserted = contactDao.insertIgnore(
                ContactEntity(
                    callsign = sender,
                    lastHeard = timestamp,
                    snr = snr,
                    offset = offsetHz,
                    grid = grid,
                    info = info
                )
            )
            if (inserted == -1L) {
                contactDao.updateHeard(sender, timestamp, snr, offsetHz, grid, info)
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

    /**
     * Station info from an INFO reply: "SENDER: TARGET INFO <text>".
     * The INFO? query itself carries no text and does not match.
     */
    private fun parseInfoReply(tokens: List<String>): String? {
        if (tokens.size < 4) return null
        if (!tokens[0].endsWith(":")) return null
        if (tokens[2].uppercase() != "INFO") return null
        return tokens.drop(3).joinToString(" ").takeIf { it.isNotBlank() }
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
