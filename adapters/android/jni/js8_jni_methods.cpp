#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <limits>
#include <mutex>
#include <string>
#include <strings.h>
#include <vector>
#include "hamlib/rig.h"
#include "js8_engine_jni.h"

// JNI method implementations for com.js8call.core.JS8Engine

namespace {
bool validate_audio_samples(JNIEnv* env,
                            jshortArray samples,
                            jint num_samples,
                            const char* label) {
  if (!env || !samples) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                        "%s samples array is null", label);
    return false;
  }

  jsize const array_length = env->GetArrayLength(samples);
  if (num_samples <= 0 || num_samples > array_length) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                        "Invalid %s sample count: count=%d array_length=%d",
                        label, num_samples, array_length);
    return false;
  }

  auto const count = static_cast<size_t>(num_samples);
  if (count > std::numeric_limits<size_t>::max() / sizeof(int16_t)) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                        "%s sample byte count overflow: count=%d",
                        label, num_samples);
    return false;
  }

  return true;
}

std::string to_utf8(JNIEnv* env, jstring value) {
  if (!env || !value) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) return {};
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

struct RigModelEntry {
  int model = 0;
  std::string mfg;
  std::string name;
};

std::once_flag g_hamlib_loaded;

int collect_rig_caps(const struct rig_caps* caps, void* data) {
  if (!caps || !data) return -1;
  if (caps->rig_model == RIG_MODEL_NONE) return -1;
  if (!caps->mfg_name || !caps->model_name) return -1;

  auto* list = static_cast<std::vector<RigModelEntry>*>(data);
  RigModelEntry entry;
  entry.model = static_cast<int>(caps->rig_model);
  entry.mfg = caps->mfg_name;
  entry.name = caps->model_name;
  list->push_back(std::move(entry));
  return -1;
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_js8call_core_JS8Engine_00024Companion_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jobject callback_handler,
    jint sample_rate_hz,
    jint submodes,
    jboolean enable_tx_audio_tap) {
  JS8Engine_Native* engine = js8_engine_create(
      env, callback_handler, sample_rate_hz, submodes,
      enable_tx_audio_tap == JNI_TRUE ? 1 : 0);
  return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeStart(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return js8_engine_start(engine) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeStop(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_stop(engine);
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_destroy(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeSubmitAudio(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray samples,
    jint num_samples,
    jlong timestamp_ns) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);

  if (!validate_audio_samples(env, samples, num_samples, "audio")) {
    return JNI_FALSE;
  }

  // Get array elements
  jshort* sample_data = env->GetShortArrayElements(samples, nullptr);
  if (!sample_data) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                       "Failed to get audio samples array");
    return JNI_FALSE;
  }

  // Log first submission with details
  static int submit_count = 0;
  if (submit_count++ % 100 == 0) {  // Every 100th submission
    if (num_samples >= 3) {
      __android_log_print(ANDROID_LOG_DEBUG, "JS8Engine_JNI",
                          "Audio submit: %d samples, first3=[%d, %d, %d]",
                          num_samples, sample_data[0], sample_data[1], sample_data[2]);
    } else {
      __android_log_print(ANDROID_LOG_DEBUG, "JS8Engine_JNI",
                          "Audio submit: %d samples", num_samples);
    }
  }

  int result = js8_engine_submit_audio(
      engine,
      reinterpret_cast<const int16_t*>(sample_data),
      static_cast<size_t>(num_samples),
      timestamp_ns);

  // Release array (no need to copy back, read-only)
  env->ReleaseShortArrayElements(samples, sample_data, JNI_ABORT);

  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeSubmitAudioRaw(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray samples,
    jint num_samples,
    jint input_sample_rate_hz,
    jlong timestamp_ns) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);

  if (!validate_audio_samples(env, samples, num_samples, "raw audio")) {
    return JNI_FALSE;
  }

  jshort* sample_data = env->GetShortArrayElements(samples, nullptr);
  if (!sample_data) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                       "Failed to get raw audio samples array");
    return JNI_FALSE;
  }

  int result = js8_engine_submit_audio_raw(
      engine,
      reinterpret_cast<const int16_t*>(sample_data),
      static_cast<size_t>(num_samples),
      static_cast<int>(input_sample_rate_hz),
      timestamp_ns);

  env->ReleaseShortArrayElements(samples, sample_data, JNI_ABORT);

  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetFrequency(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jlong frequency_hz) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_frequency(engine, static_cast<uint64_t>(frequency_hz));
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetSubmodes(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint submodes) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_submodes(engine, submodes);
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetOutputDevice(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint device_id) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_output_device(engine, static_cast<int>(device_id));
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetTxBoostEnabled(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jboolean enabled) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_tx_boost_enabled(engine, static_cast<bool>(enabled));
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetTimeDriftMs(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jlong drift_ms) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_time_drift_ms(engine, static_cast<long long>(drift_ms));
}

JNIEXPORT jlong JNICALL
Java_com_js8call_core_JS8Engine_nativeGetTimeDriftMs(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return static_cast<jlong>(js8_engine_get_time_drift_ms(engine));
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeTransmitMessage(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring text,
    jstring my_call,
    jstring my_grid,
    jstring selected_call,
    jint submode,
    jdouble audio_frequency_hz,
    jdouble tx_delay_s,
    jboolean force_identify,
    jboolean force_data) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  auto text_utf8 = to_utf8(env, text);
  auto my_call_utf8 = to_utf8(env, my_call);
  auto my_grid_utf8 = to_utf8(env, my_grid);
  auto selected_call_utf8 = to_utf8(env, selected_call);

  int result = js8_engine_transmit_message(
      engine,
      text_utf8.c_str(),
      my_call_utf8.c_str(),
      my_grid_utf8.c_str(),
      selected_call_utf8.c_str(),
      static_cast<int>(submode),
      static_cast<double>(audio_frequency_hz),
      static_cast<double>(tx_delay_s),
      force_identify ? 1 : 0,
      force_data ? 1 : 0);

  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeTransmitFrame(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring frame,
    jint bits,
    jint submode,
    jdouble audio_frequency_hz,
    jdouble tx_delay_s) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  auto frame_utf8 = to_utf8(env, frame);
  int result = js8_engine_transmit_frame(
      engine,
      frame_utf8.c_str(),
      static_cast<int>(bits),
      static_cast<int>(submode),
      static_cast<double>(audio_frequency_hz),
      static_cast<double>(tx_delay_s));
  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeStartTune(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jdouble audio_frequency_hz,
    jint submode,
    jdouble tx_delay_s) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  int result = js8_engine_start_tune(
      engine,
      static_cast<double>(audio_frequency_hz),
      static_cast<int>(submode),
      static_cast<double>(tx_delay_s));
  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeStopTransmit(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_stop_transmit(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeIsTransmitting(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return js8_engine_is_transmitting(engine) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeIsTransmittingAudio(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return js8_engine_is_transmitting_audio(engine) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_js8call_core_JS8Engine_nativeTxMillisecondsUntilAudio(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return static_cast<jint>(js8_engine_tx_milliseconds_until_audio(engine));
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetTxReady(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jboolean ready) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_tx_ready(engine, ready == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeIsRunning(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return js8_engine_is_running(engine) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_js8call_core_HamlibRigCatalog_nativeListRigModels(
    JNIEnv* env,
    jobject /* thiz */) {
  if (!env) {
    return nullptr;
  }

  std::call_once(g_hamlib_loaded, []() {
    rig_load_all_backends();
  });

  std::vector<RigModelEntry> list;
  int status = rig_list_foreach(collect_rig_caps, &list);
  if (status != RIG_OK) {
    __android_log_print(ANDROID_LOG_WARN, "HamlibRigCatalog",
                        "rig_list_foreach failed: %s", rigerror(status));
  }

  std::sort(list.begin(), list.end(), [](RigModelEntry const& a, RigModelEntry const& b) {
    int cmp = strcasecmp(a.mfg.c_str(), b.mfg.c_str());
    if (cmp != 0) return cmp < 0;
    cmp = strcasecmp(a.name.c_str(), b.name.c_str());
    if (cmp != 0) return cmp < 0;
    return a.model < b.model;
  });

  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class) {
    return nullptr;
  }

  jsize out_size = static_cast<jsize>(list.size() + 1);
  jobjectArray result = env->NewObjectArray(out_size, string_class, nullptr);
  if (!result) {
    return nullptr;
  }

  jstring none_entry = env->NewStringUTF("0|None");
  env->SetObjectArrayElement(result, 0, none_entry);
  env->DeleteLocalRef(none_entry);

  for (size_t i = 0; i < list.size(); ++i) {
    const auto& entry = list[i];
    std::string label = entry.mfg;
    if (!label.empty()) {
      label.push_back(' ');
    }
    label += entry.name;
    std::string packed = std::to_string(entry.model) + "|" + label;
    jstring packed_str = env->NewStringUTF(packed.c_str());
    env->SetObjectArrayElement(result, static_cast<jsize>(i + 1), packed_str);
    env->DeleteLocalRef(packed_str);
  }

  return result;
}

}  // extern "C"
