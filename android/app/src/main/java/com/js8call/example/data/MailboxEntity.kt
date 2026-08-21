package com.js8call.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A store-and-forward message this station holds for someone else.
 *
 * These are deliberately not [MessageEntity] rows: a held message has an
 * originator and a destination, neither of which is us, and putting it in
 * the messages table would manufacture phantom conversations.
 *
 * The id is AUTOINCREMENT rather than plain rowid so a deleted id is never
 * reused. Peers ask for messages by id, and a stale query must miss rather
 * than hit someone else's mail.
 */
@Entity(
    tableName = "mailbox_messages",
    indices = [
        Index(value = ["destination"]),
        Index(value = ["state"]),
        Index(value = ["receivedAt"])
    ]
)
data class MailboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Callsign that deposited the message */
    val originator: String,

    /** Callsign or @GROUP the message is for */
    val destination: String,

    val text: String,

    /** UTC milliseconds when we accepted the message */
    val receivedAt: Long,

    /** Originator's UTC milliseconds, when the deposit carried one */
    val originatedAt: Long? = null,

    /** Relay hops in CALL1>CALL2 notation, when the deposit came via relay */
    val relayPath: String? = null,

    /** SNR of the depositing transmission */
    val snr: Int? = null,

    /** Audio frequency offset in Hz of the depositing transmission */
    val offsetHz: Float? = null,

    /** One of [STATE_HELD], [STATE_DELIVERED], [STATE_EXPIRED] */
    val state: Int = STATE_HELD,

    /** UTC milliseconds of delivery, individual destinations only */
    val deliveredAt: Long? = null,

    /** [ORIGIN_DEPOSITED] or [ORIGIN_COMPOSED] */
    val origin: Int = ORIGIN_DEPOSITED
) {
    companion object {
        const val STATE_HELD = 0
        const val STATE_DELIVERED = 1
        const val STATE_EXPIRED = 2

        /** Another station deposited it with us over the air */
        const val ORIGIN_DEPOSITED = 0

        /** Composed here, waiting to be deposited at a relay */
        const val ORIGIN_COMPOSED = 1
    }
}
