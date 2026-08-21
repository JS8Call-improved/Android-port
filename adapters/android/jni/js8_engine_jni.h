#pragma once

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque handle to the native engine instance
typedef struct JS8Engine_Native JS8Engine_Native;

// Engine creation and lifecycle
JS8Engine_Native* js8_engine_create(JNIEnv* env, jobject callback_handler, int sample_rate_hz, int submodes, int enable_tx_audio_tap);
void js8_engine_destroy(JS8Engine_Native* engine);
int js8_engine_start(JS8Engine_Native* engine);
void js8_engine_stop(JS8Engine_Native* engine);

// Audio submission
int js8_engine_submit_audio(JS8Engine_Native* engine, const int16_t* samples, size_t num_samples, int64_t timestamp_ns);
int js8_engine_submit_audio_raw(JS8Engine_Native* engine, const int16_t* samples, size_t num_samples, int input_sample_rate, int64_t timestamp_ns);

// Configuration
void js8_engine_set_frequency(JS8Engine_Native* engine, uint64_t frequency_hz);
void js8_engine_set_submodes(JS8Engine_Native* engine, int submodes);
void js8_engine_set_output_device(JS8Engine_Native* engine, int device_id);
void js8_engine_set_tx_boost_enabled(JS8Engine_Native* engine, bool enabled);
void js8_engine_set_time_drift_ms(JS8Engine_Native* engine, long long drift_ms);
long long js8_engine_get_time_drift_ms(JS8Engine_Native* engine);

// Transmit control
int js8_engine_transmit_message(JS8Engine_Native* engine,
                                const char* text,
                                const char* my_call,
                                const char* my_grid,
                                const char* selected_call,
                                int submode,
                                double audio_frequency_hz,
                                double tx_delay_s,
                                int force_identify,
                                int force_data);
int js8_engine_transmit_frame(JS8Engine_Native* engine,
                              const char* frame,
                              int bits,
                              int submode,
                              double audio_frequency_hz,
                              double tx_delay_s);
int js8_engine_start_tune(JS8Engine_Native* engine,
                          double audio_frequency_hz,
                          int submode,
                          double tx_delay_s);
void js8_engine_stop_transmit(JS8Engine_Native* engine);
int js8_engine_is_transmitting(JS8Engine_Native* engine);
int js8_engine_is_transmitting_audio(JS8Engine_Native* engine);
int js8_engine_tx_milliseconds_until_audio(JS8Engine_Native* engine);
int js8_engine_tx_frame_index(JS8Engine_Native* engine);
int js8_engine_tx_frame_count(JS8Engine_Native* engine);
void js8_engine_set_tx_ready(JS8Engine_Native* engine, bool ready);

// Status queries
int js8_engine_is_running(JS8Engine_Native* engine);

// JNI registration
int js8_register_natives(JavaVM* vm, JNIEnv* env);

#ifdef __cplusplus
}
#endif
