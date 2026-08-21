package com.js8call.example.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for the store-and-forward mailbox.
 *
 * The next/lookahead queries drive the QUERY MSGS and QUERY MSG {id}
 * replies. Both take an afterId cursor: pass 0 for the first message,
 * then the id just delivered to walk the NEXT MSG ID chain.
 */
@Dao
interface MailboxDao {

    // ---- serving an individual station ----

    @Query(
        """
        SELECT * FROM mailbox_messages
        WHERE state = 0 AND destination = :callsign AND id > :afterId
        ORDER BY id ASC LIMIT 1
        """
    )
    suspend fun nextForCallsign(callsign: String, afterId: Long = 0): MailboxEntity?

    // ---- serving a group ----

    /**
     * The next group message [callsign] has not collected yet. Only messages
     * received after [since] are offered; desktop limits group retrieval to
     * the last 48 hours.
     */
    @Query(
        """
        SELECT m.* FROM mailbox_messages m
        LEFT JOIN mailbox_group_delivery d
            ON d.msgId = m.id AND d.callsign = :callsign
        WHERE m.state = 0 AND m.destination = :groupName
            AND m.receivedAt >= :since AND m.id > :afterId
            AND d.callsign IS NULL
        ORDER BY m.id ASC LIMIT 1
        """
    )
    suspend fun nextGroupForCallsign(
        groupName: String,
        callsign: String,
        since: Long,
        afterId: Long = 0
    ): MailboxEntity?

    // ---- state changes ----

    @Insert
    suspend fun insert(message: MailboxEntity): Long

    @Query("UPDATE mailbox_messages SET state = 1, deliveredAt = :at WHERE id = :id")
    suspend fun markDelivered(id: Long, at: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordGroupDelivery(delivery: MailboxGroupDeliveryEntity)

    // ---- the Held messages screen ----

    @Query("SELECT * FROM mailbox_messages ORDER BY receivedAt DESC")
    fun getAll(): LiveData<List<MailboxEntity>>

    @Query("SELECT COUNT(*) FROM mailbox_messages WHERE state = 0")
    fun getHeldCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM mailbox_group_delivery WHERE msgId = :msgId")
    suspend fun deliveryCount(msgId: Long): Int

    @Query("DELETE FROM mailbox_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM mailbox_messages WHERE state = 1")
    suspend fun deleteDelivered()
}
