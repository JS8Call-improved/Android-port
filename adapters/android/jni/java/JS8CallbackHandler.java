package com.js8call.core;

/**
 * Java interface for JS8 engine callbacks.
 * Implement this interface to receive events from the native engine.
 *
 * Note: All callbacks are invoked on a native thread, not the UI thread.
 * Use Handler.post() or similar to update UI elements.
 */
public interface JS8CallbackHandler {

    /**
     * Called when a message is successfully decoded.
     *
     * @param utc UTC time of decode
     * @param snr Signal-to-noise ratio in dB
     * @param dt Time offset in seconds
     * @param freq Frequency offset in Hz
     * @param text Decoded message text
     * @param type Message type identifier
     * @param quality Decode quality metric (0-1)
     * @param mode Submode identifier
     * @param driftMs Suggested total clock drift (ms) that would center this
     *                signal's cycle; see JS8Engine.setTimeDriftMs()
     */
    void onDecoded(int utc, int snr, float dt, float freq,
                   String text, int type, float quality, int mode, int driftMs);

    /**
     * Called with FFT spectrum data for waterfall display.
     *
     * @param bins Array of FFT bin magnitudes
     * @param binHz Frequency spacing per bin in Hz
     * @param powerDb Average power level in dB
     * @param peakDb Peak power level in dB
     */
    void onSpectrum(float[] bins, float binHz, float powerDb, float peakDb);

    /**
     * Called when a decode cycle begins.
     *
     * @param submodes Bitmask of submodes being decoded
     */
    void onDecodeStarted(int submodes);

    /**
     * Called when a decode cycle completes.
     *
     * @param count Number of messages decoded in this cycle
     */
    void onDecodeFinished(int count);

    /**
     * Called when an error occurs in the engine.
     *
     * @param message Error description
     */
    void onError(String message);

    /**
     * Called for diagnostic log messages.
     *
     * @param level Log level: 0=Trace, 1=Debug, 2=Info, 3=Warn, 4=Error
     * @param message Log message text
     */
    void onLog(int level, String message);
}
