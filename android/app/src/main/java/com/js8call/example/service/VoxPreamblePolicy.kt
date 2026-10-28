package com.js8call.example.service

/** Rig-controlled PTT must never use the audio-only VOX preamble. */
internal fun voxPreambleDurationMs(rigControlMode: String, configuredSeconds: Int, rigControlEnabled: Boolean = false): Int {
    return if (!rigControlEnabled && rigControlMode == "none") configuredSeconds.coerceIn(0, 5) * 1000 else 0
}
