package com.js8call.example.data

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for the store-and-forward mailbox: messages this station
 * holds for other operators, and messages composed here awaiting deposit
 * at a relay.
 */
class MailboxRepository(context: Context) {

    private val mailboxDao = MessageDatabase.getInstance(context).mailboxDao()

    fun getAll(): LiveData<List<MailboxEntity>> = mailboxDao.getAll()

    fun getHeldCount(): LiveData<Int> = mailboxDao.getHeldCount()

    suspend fun store(message: MailboxEntity): Long =
        withContext(Dispatchers.IO) { mailboxDao.insert(message) }

    /** The next held message for [callsign] with id above [afterId]. */
    suspend fun nextForCallsign(callsign: String, afterId: Long = 0): MailboxEntity? =
        withContext(Dispatchers.IO) {
            mailboxDao.nextForCallsign(callsign.trim().uppercase(), afterId)
        }

    /**
     * The next group message [callsign] has not collected, no older than
     * [GROUP_RETRIEVAL_WINDOW_MS]. Any station may collect group mail;
     * delivery is per callsign and the message is not consumed.
     */
    suspend fun nextGroupForCallsign(
        groupName: String,
        callsign: String,
        afterId: Long = 0,
        now: Long = System.currentTimeMillis()
    ): MailboxEntity? = withContext(Dispatchers.IO) {
        mailboxDao.nextGroupForCallsign(
            groupName.trim().uppercase(),
            callsign.trim().uppercase(),
            now - GROUP_RETRIEVAL_WINDOW_MS,
            afterId
        )
    }

    /**
     * The next message [callsign] may collect, individual or group, with id
     * above [afterId]. Drives QUERY MSGS and the NEXT MSG ID lookahead.
     */
    suspend fun nextForRecipient(
        callsign: String,
        afterId: Long = 0,
        now: Long = System.currentTimeMillis()
    ): MailboxEntity? = withContext(Dispatchers.IO) {
        mailboxDao.nextForRecipient(
            callsign.trim().uppercase(),
            now - GROUP_RETRIEVAL_WINDOW_MS,
            afterId
        )
    }

    /**
     * The message behind a QUERY MSG {id}, or null when [callsign] may not
     * collect it: unknown id, delivered already, someone else's mail, or a
     * group message outside the retrieval window or collected before.
     */
    suspend fun getEligible(
        id: Long,
        callsign: String,
        now: Long = System.currentTimeMillis()
    ): MailboxEntity? = withContext(Dispatchers.IO) {
        val call = callsign.trim().uppercase()
        val msg = mailboxDao.getById(id) ?: return@withContext null
        if (msg.state != MailboxEntity.STATE_HELD) return@withContext null
        when {
            msg.destination == call -> msg
            msg.destination.startsWith("@") &&
                msg.receivedAt >= now - GROUP_RETRIEVAL_WINDOW_MS &&
                mailboxDao.hasGroupDelivery(id, call) == 0 -> msg
            else -> null
        }
    }

    suspend fun markDelivered(id: Long, at: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) { mailboxDao.markDelivered(id, at) }
    }

    suspend fun recordGroupDelivery(msgId: Long, callsign: String, at: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            mailboxDao.recordGroupDelivery(
                MailboxGroupDeliveryEntity(msgId, callsign.trim().uppercase(), at)
            )
        }
    }

    suspend fun deliveryCount(msgId: Long): Int =
        withContext(Dispatchers.IO) { mailboxDao.deliveryCount(msgId) }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { mailboxDao.delete(id) }
    }

    suspend fun deleteDelivered() {
        withContext(Dispatchers.IO) { mailboxDao.deleteDelivered() }
    }

    companion object {
        /** Desktop offers group mail for 48 hours after storage. */
        const val GROUP_RETRIEVAL_WINDOW_MS = 48L * 60 * 60 * 1000
    }
}
