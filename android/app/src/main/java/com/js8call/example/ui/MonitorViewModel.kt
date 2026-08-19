package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.js8call.example.model.EngineState
import com.js8call.example.model.MonitorStatus
import com.js8call.example.model.SpectrumData

/**
 * ViewModel for the Monitor screen.
 * Manages engine state and monitoring status.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val _status = MutableLiveData<MonitorStatus>()
    val status: LiveData<MonitorStatus> = _status

    private val _spectrum = MutableLiveData<SpectrumData>()
    val spectrum: LiveData<SpectrumData> = _spectrum

    private val _isRunning = MutableLiveData<Boolean>(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _txOffsetHz = MutableLiveData<Float>(1500f)
    val txOffsetHz: LiveData<Float> = _txOffsetHz

    private val _radioFrequency = MutableLiveData<Long>()
    val radioFrequency: LiveData<Long> = _radioFrequency

    private val waterfallRenderer = WaterfallRenderer()

    init {
        _status.value = MonitorStatus(state = EngineState.STOPPED)
    }

    /**
     * Start monitoring.
     */
    fun startMonitoring() {
        _status.value = _status.value?.copy(state = EngineState.STARTING)
        // Service will be started by fragment
    }

    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        _status.value = _status.value?.copy(state = EngineState.STOPPED)
        _isRunning.value = false
        waterfallRenderer.clear()
        // Service will be stopped by fragment
    }

    /**
     * Update engine state (called from service callbacks).
     */
    fun updateState(state: EngineState, errorMessage: String? = null) {
        _status.value = _status.value?.copy(
            state = state,
            errorMessage = errorMessage
        )
        _isRunning.value = (state == EngineState.RUNNING)
        if (state == EngineState.STOPPED || state == EngineState.ERROR) {
            waterfallRenderer.clear()
        }
    }

    /**
     * Update spectrum data from engine.
     */
    fun updateSpectrum(bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float) {
        waterfallRenderer.updateSpectrum(bins, binHz)
        _spectrum.value = SpectrumData(bins, binHz, powerDb, peakDb)

        // Update power levels in status
        _status.value = _status.value?.copy(
            powerDb = powerDb,
            peakDb = peakDb
        )
    }

    /**
     * Update SNR value.
     */
    fun updateSnr(snr: Int) {
        _status.value = _status.value?.copy(snr = snr)
    }

    /**
     * Update frequency.
     */
    fun updateFrequency(frequency: Long) {
        _status.value = _status.value?.copy(frequency = frequency)
        _radioFrequency.value = frequency
    }

    /**
     * Update audio device name.
     */
    fun updateAudioDevice(deviceName: String) {
        _status.value = _status.value?.copy(audioDevice = deviceName)
    }

    /**
     * Update the applied time drift.
     */
    fun updateTimeDrift(driftMs: Long) {
        _status.value = _status.value?.copy(timeDriftMs = driftMs)
    }

    /**
     * Set the TX offset frequency in Hz.
     */
    fun setTxOffset(offsetHz: Float) {
        _txOffsetHz.value = offsetHz
        _status.value = _status.value?.copy(txOffsetHz = offsetHz)
    }

    /**
     * Get the current TX offset frequency in Hz.
     */
    fun getTxOffset(): Float {
        return _txOffsetHz.value ?: 1500f
    }

    fun getWaterfallRenderer(): WaterfallRenderer = waterfallRenderer

    /**
     * Handle error.
     */
    fun onError(message: String) {
        _status.value = _status.value?.copy(
            state = EngineState.ERROR,
            errorMessage = message
        )
        _isRunning.value = false
        waterfallRenderer.clear()
    }

    fun clearError() {
        val current = _status.value ?: return
        if (current.errorMessage == null) return
        _status.value = current.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        waterfallRenderer.release()
    }
}
