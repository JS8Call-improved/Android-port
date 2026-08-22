package com.js8call.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-thread settings that are the operator's choice rather than something
 * heard on the air. Kept apart from [ContactEntity], whose rows are rewritten
 * on every decode.
 */
@Entity(tableName = "conversation_settings")
data class ConversationSettingsEntity(

    /** Callsign or @GROUP name, matching MessageEntity.conversationId */
    @PrimaryKey
    val conversationId: String,

    /**
     * Ordered relay hops in `A>B` notation, nearest hop first, excluding the
     * destination. Null or empty means transmit direct.
     */
    val relayPath: String? = null
)
