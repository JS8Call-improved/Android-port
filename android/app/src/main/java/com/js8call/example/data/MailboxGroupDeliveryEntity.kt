package com.js8call.example.data

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One retrieval of a group-addressed mailbox message by one station.
 *
 * A group message is never consumed by delivery. Any station may retrieve
 * it, so delivery is recorded per callsign here, and the selection queries
 * exclude only the messages a given station has already collected. This is
 * desktop's inbox_group_recip_v1 table.
 */
@Entity(
    tableName = "mailbox_group_delivery",
    primaryKeys = ["msgId", "callsign"],
    foreignKeys = [
        ForeignKey(
            entity = MailboxEntity::class,
            parentColumns = ["id"],
            childColumns = ["msgId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MailboxGroupDeliveryEntity(
    val msgId: Long,
    val callsign: String,
    val deliveredAt: Long
)
