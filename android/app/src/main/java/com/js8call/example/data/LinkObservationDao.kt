package com.js8call.example.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LinkObservationDao {

    @Insert
    suspend fun insertAll(observations: List<LinkObservationEntity>)

    @Query("SELECT * FROM link_observations WHERE observedAt >= :since ORDER BY observedAt DESC")
    suspend fun getSince(since: Long): List<LinkObservationEntity>

    @Query("SELECT * FROM link_observations WHERE (reporter = :callsign OR heard = :callsign) AND observedAt >= :since ORDER BY observedAt DESC")
    suspend fun getForStation(callsign: String, since: Long): List<LinkObservationEntity>

    @Query("SELECT COUNT(*) FROM link_observations")
    fun countLive(): LiveData<Int>

    @Query("DELETE FROM link_observations WHERE observedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM link_observations")
    suspend fun deleteAll()
}
