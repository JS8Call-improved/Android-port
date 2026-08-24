package com.js8call.example.model

/**
 * Represents the state of the JS8 engine.
 */
enum class EngineState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Monitoring status information.
 */
 data class MonitorStatus(
    val state: EngineState = EngineState.STOPPED,
    val snr: Int = 0,
    val powerDb: Float = 0f,
    val peakDb: Float = 0f,
    val frequency: Long = 14078000, // Default to 20m JS8 frequency
    val audioDevice: String = "Unknown",
    val txOffsetHz: Float = 1500f, // Default TX offset
    val timeDriftMs: Long = 0L,
    val errorMessage: String? = null
) {
    val isRunning: Boolean
        get() = state == EngineState.RUNNING

    val isStopped: Boolean
        get() = state == EngineState.STOPPED
}

/**
 * Spectrum data for waterfall display.
 */
data class SpectrumData(
    val bins: FloatArray,
    val binHz: Float,
    val powerDb: Float,
    val peakDb: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpectrumData

        if (!bins.contentEquals(other.bins)) return false
        if (binHz != other.binHz) return false
        if (powerDb != other.powerDb) return false
        if (peakDb != other.peakDb) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bins.contentHashCode()
        result = 31 * result + binHz.hashCode()
        result = 31 * result + powerDb.hashCode()
        result = 31 * result + peakDb.hashCode()
        return result
    }
}

/**
 * Transmit queue item.
 */
data class TransmitMessage(
    val text: String,
    val directed: String? = null,
    val priority: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** Row id in the message database, when this send belongs to a conversation. */
    val dbId: Long? = null,
    /** Mailbox row this send delivers, marked once the transmission finishes. */
    val mailboxId: Long? = null,
    /** Set for a group delivery: the collecting callsign to record. */
    val mailboxRecipient: String? = null
)

/**
 * TX status.
 */
enum class TransmitState {
    IDLE,
    QUEUED,
    TRANSMITTING
}
