package com.js8call.example.data

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for link observations, the who-hears-whom evidence behind the
 * network map. Writing happens in the service as frames are decoded; pruning
 * runs on service start against the operator's retention setting.
 */
class LinkRepository(context: Context) {

    private val dao = MessageDatabase.getInstance(context).linkObservationDao()

    suspend fun record(observations: List<LinkObservationEntity>) {
        if (observations.isEmpty()) return
        withContext(Dispatchers.IO) { dao.insertAll(observations) }
    }

    suspend fun getSince(since: Long): List<LinkObservationEntity> =
        withContext(Dispatchers.IO) { dao.getSince(since) }

    suspend fun getForStation(callsign: String, since: Long): List<LinkObservationEntity> =
        withContext(Dispatchers.IO) { dao.getForStation(callsign.trim().uppercase(), since) }

    fun countLive(): LiveData<Int> = dao.countLive()

    suspend fun pruneOlderThan(retentionMs: Long) {
        withContext(Dispatchers.IO) { dao.deleteOlderThan(System.currentTimeMillis() - retentionMs) }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { dao.deleteAll() }
    }

    companion object {
        @Volatile
        private var INSTANCE: LinkRepository? = null

        fun getInstance(context: Context): LinkRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LinkRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
