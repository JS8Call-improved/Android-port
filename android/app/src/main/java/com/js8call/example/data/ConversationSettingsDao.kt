package com.js8call.example.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationSettingsDao {

    @Query("SELECT relayPath FROM conversation_settings WHERE conversationId = :conversationId")
    fun getRelayPath(conversationId: String): LiveData<String?>

    @Query("SELECT relayPath FROM conversation_settings WHERE conversationId = :conversationId")
    suspend fun getRelayPathOnce(conversationId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ConversationSettingsEntity)

    @Query("DELETE FROM conversation_settings WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}
