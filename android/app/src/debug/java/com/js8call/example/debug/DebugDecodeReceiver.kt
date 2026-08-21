package com.js8call.example.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.js8call.example.service.JS8EngineService

/**
 * Debug-only bridge from adb to the decode path. The engine service is not
 * exported, so the shell cannot start it directly; this receiver is, and
 * forwards the extras on.
 *
 *   adb shell am broadcast -a com.js8call.example.DEBUG_INJECT_DECODE \
 *       -n com.js8call.example/.debug.DebugDecodeReceiver \
 *       --es text 'KN4CRD: N0CALL MSG TO:KA0XYZ HELLO' --ei type 1
 *
 * Extras mirror the decode broadcast: text, snr, freq, type, mode.
 *
 * The app must be foregrounded when the broadcast arrives; a background
 * startService is refused and the receiver crash takes the process with it.
 */
class DebugDecodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val forward = Intent(context, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_DEBUG_INJECT_DECODE
            intent.extras?.let { putExtras(it) }
        }
        context.startService(forward)
    }
}
