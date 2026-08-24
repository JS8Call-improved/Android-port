package com.js8call.example.data

import androidx.lifecycle.LiveData
import androidx.room.*

/**
 * Data Access Object for message operations.
 */
@Dao
interface MessageDao {

    // ========== Message Operations ==========

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): LiveData<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationSync(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): LiveData<List<MessageEntity>>

    // ========== Message Status Updates ==========

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: Long, status: Int)

    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :conversationId AND isRead = 0")
    suspend fun markConversationAsRead(conversationId: String)

    /**
     * An inbound ACK carries no message id, so it is taken as a receipt
     * for the newest sent message in that conversation.
     */
    @Query(
        """
        UPDATE messages SET status = :acked WHERE id = (
            SELECT id FROM messages
            WHERE conversationId = :conversationId AND direction = 1 AND status = :sent
            ORDER BY timestamp DESC LIMIT 1
        )
        """
    )
    suspend fun markLatestSentAcked(conversationId: String, sent: Int, acked: Int)

    /**
     * Prune stored group traffic the operator never subscribed to.
     * Subscribed groups are passed in [keep] and left alone.
     */
    @Query(
        """
        DELETE FROM messages
        WHERE conversationId LIKE '@%'
            AND conversationId NOT IN (:keep)
            AND timestamp < :cutoff
        """
    )
    suspend fun deleteOldGroupMessages(cutoff: Long, keep: List<String>)

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markMessageAsRead(messageId: Long)

    // ========== Conversation List Queries ==========

    /**
     * Get all conversations with summary info for the conversation list.
     * Groups messages by conversationId and returns the most recent message info.
     */
    @Query("""
        SELECT 
            conversationId as callsign,
            text as lastMessage,
            timestamp as lastTimestamp,
            (SELECT COUNT(*) FROM messages m2 
             WHERE m2.conversationId = m1.conversationId 
             AND m2.isRead = 0 
             AND m2.direction = 0) as unreadCount
        FROM messages m1
        WHERE timestamp = (
            SELECT MAX(timestamp) FROM messages m3 
            WHERE m3.conversationId = m1.conversationId
        )
        GROUP BY conversationId
        ORDER BY timestamp DESC
    """)
    fun getConversations(): LiveData<List<ConversationSummary>>

    @Query("""
        SELECT 
            conversationId as callsign,
            text as lastMessage,
            timestamp as lastTimestamp,
            (SELECT COUNT(*) FROM messages m2 
             WHERE m2.conversationId = m1.conversationId 
             AND m2.isRead = 0 
             AND m2.direction = 0) as unreadCount
        FROM messages m1
        WHERE timestamp = (
            SELECT MAX(timestamp) FROM messages m3 
            WHERE m3.conversationId = m1.conversationId
        )
        GROUP BY conversationId
        ORDER BY timestamp DESC
    """)
    suspend fun getConversationsSync(): List<ConversationSummary>

    // ========== Unread Count Queries ==========

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0 AND direction = 0")
    fun getTotalUnreadCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0 AND direction = 0")
    suspend fun getTotalUnreadCountSync(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isRead = 0 AND direction = 0")
    suspend fun getUnreadCountForConversation(conversationId: String): Int

    @Query("SELECT conversationId FROM messages GROUP BY conversationId ORDER BY MAX(timestamp) DESC")
    fun getConversationCallsigns(): LiveData<List<String>>

    // ========== Search ==========

    @Query("SELECT * FROM messages WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessages(query: String): LiveData<List<MessageEntity>>

    // ========== Utility ==========

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId = :conversationId LIMIT 1)")
    suspend fun conversationExists(conversationId: String): Boolean

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?
}
