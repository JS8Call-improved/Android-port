package com.js8call.example.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.js8call.example.service.JS8EngineService

/** Forwards adb decode injections to [JS8EngineService]. */
class DebugDecodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val forward = Intent(context, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_DEBUG_INJECT_DECODE
            intent.extras?.let { putExtras(it) }
        }
        try {
            context.startService(forward)
        } catch (e: IllegalStateException) {
            // Refused from the background unless the engine is running
            Log.w(TAG, "Injected decode dropped: bring the app to the front")
        }
    }

    companion object {
        private const val TAG = "DebugDecodeReceiver"
    }
}
