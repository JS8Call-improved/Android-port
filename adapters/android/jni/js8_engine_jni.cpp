#include "js8_engine_jni.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cctype>
#include <chrono>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "js8core/engine.hpp"
#include "js8core/protocol/varicode.hpp"
#include "js8core/dsp/resampler.hpp"
#include "js8core/android/audio_oboe.hpp"
#include "js8core/android/logger_android.hpp"
#include "js8core/android/network_android.hpp"
#include "js8core/android/rig_android.hpp"
#include "js8core/android/scheduler_android.hpp"
#include "js8core/android/storage_android.hpp"

// Forward declarations of JNI methods (defined in js8_jni_methods.cpp)
extern "C" {
JNIEXPORT jlong JNICALL Java_com_js8call_core_JS8Engine_00024Companion_nativeCreate(JNIEnv*, jobject, jobject, jint, jint, jboolean);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeStart(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeStop(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeDestroy(JNIEnv*, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeSubmitAudio(JNIEnv*, jobject, jlong, jshortArray, jint, jlong);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeSubmitAudioRaw(JNIEnv*, jobject, jlong, jshortArray, jint, jint, jlong);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeSetFrequency(JNIEnv*, jobject, jlong, jlong);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeSetSubmodes(JNIEnv*, jobject, jlong, jint);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeSetOutputDevice(JNIEnv*, jobject, jlong, jint);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeSetTxBoostEnabled(JNIEnv*, jobject, jlong, jboolean);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeIsRunning(JNIEnv*, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeTransmitMessage(JNIEnv*, jobject, jlong, jstring, jstring, jstring, jstring, jint, jdouble, jdouble, jboolean, jboolean);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeTransmitFrame(JNIEnv*, jobject, jlong, jstring, jint, jint, jdouble, jdouble);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeStartTune(JNIEnv*, jobject, jlong, jdouble, jint, jdouble);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeStopTransmit(JNIEnv*, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeIsTransmitting(JNIEnv*, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_js8call_core_JS8Engine_nativeIsTransmittingAudio(JNIEnv*, jobject, jlong);
JNIEXPORT jint JNICALL Java_com_js8call_core_JS8Engine_nativeTxMillisecondsUntilAudio(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_com_js8call_core_JS8Engine_nativeSetTxReady(JNIEnv*, jobject, jlong, jboolean);
}

// Global JavaVM reference for callbacks
static JavaVM* g_jvm = nullptr;

// Engine wrapper that owns all adapter instances
struct JS8Engine_Native {
  std::unique_ptr<js8core::Js8Engine> engine;
  std::unique_ptr<js8core::android::AndroidLogger> logger;
  std::unique_ptr<js8core::android::FileStorage> storage;
  std::unique_ptr<js8core::android::ThreadScheduler> scheduler;
  std::unique_ptr<js8core::android::OboeAudioInput> audio_in;
  std::unique_ptr<js8core::AudioOutput> audio_out;
  std::unique_ptr<js8core::android::BsdUdpChannel> udp;
  std::unique_ptr<js8core::android::NullRigControl> rig;

  // Java callback handler (global ref)
  jobject callback_handler = nullptr;
  std::mutex callback_mutex;

  bool tx_boost_enabled = false;
  std::mutex tx_boost_mutex;

  // Audio buffer for submission
  js8core::AudioFormat audio_format;
  std::vector<std::byte> audio_buffer;

  // Decimation state for Java-side raw audio (e.g., 48 kHz -> 12 kHz)
  int decimation_factor = 1;
  int decimation_mirror = 0;
  std::vector<float> decimation_taps;
  std::vector<int16_t> decimation_buffer;
  int decimation_pos = 0;

  // Fractional resampling state for non-integer rate conversion (e.g., 44.1 kHz -> 12 kHz)
  std::vector<float> resample_buffer;
  double resample_pos = 0.0;
  int resample_input_rate = 0;
  int resample_output_rate = 0;
};

// Helper to get JNI environment for current thread
static JNIEnv* get_jni_env() {
  if (!g_jvm) return nullptr;

  JNIEnv* env = nullptr;
  int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);

  if (status == JNI_EDETACHED) {
    // Attach current thread
    status = g_jvm->AttachCurrentThread(&env, nullptr);
    if (status != JNI_OK) {
      return nullptr;
    }
  }

  return env;
}

class PumpAudioOutput final : public js8core::AudioOutput {
 public:
  using TxAudioHandler = std::function<void(std::span<const std::int16_t>, int)>;

  explicit PumpAudioOutput(TxAudioHandler on_tx_audio)
      : on_tx_audio_(std::move(on_tx_audio)) {}

  ~PumpAudioOutput() override {
    stop();
  }

  bool start(js8core::AudioStreamParams const& params,
             js8core::AudioOutputFill fill,
             js8core::AudioErrorHandler on_error) override {
    std::lock_guard<std::mutex> lock(mutex_);
    if (running_) return false;

    params_ = params;
    if (params_.format.sample_rate <= 0) {
      params_.format.sample_rate = kDefaultRateHz;
    }
    params_.format.channels = 1;
    params_.format.sample_type = js8core::SampleType::Int16;
    if (params_.frames_per_buffer == 0) {
      params_.frames_per_buffer = kDefaultFramesPerBuffer;
    }

    fill_ = std::move(fill);
    on_error_ = std::move(on_error);
    running_ = true;
    worker_ = std::thread([this]() { this->pump_loop(); });
    return true;
  }

  void stop() override {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (!running_) return;
      running_ = false;
    }
    if (worker_.joinable()) {
      worker_.join();
    }
  }

 private:
  void pump_loop() {
    auto sample_rate = params_.format.sample_rate > 0 ? params_.format.sample_rate : kDefaultRateHz;
    std::size_t frames = params_.frames_per_buffer > 0 ? params_.frames_per_buffer : kDefaultFramesPerBuffer;
    auto frame_period = std::chrono::duration<double>(static_cast<double>(frames) /
                                                       static_cast<double>(sample_rate));
    std::vector<std::int16_t> pcm(frames, 0);
    auto next_tick = std::chrono::steady_clock::now();

    while (true) {
      {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!running_) break;
      }

      std::memset(pcm.data(), 0, pcm.size() * sizeof(std::int16_t));

      if (fill_) {
        js8core::AudioOutputBuffer buffer;
        buffer.data = std::span<std::byte>(reinterpret_cast<std::byte*>(pcm.data()),
                                           pcm.size() * sizeof(std::int16_t));
        buffer.format = params_.format;
        buffer.playback_at = js8core::SteadyClock::now();
        try {
          std::size_t filled = fill_(buffer);
          std::size_t max_bytes = pcm.size() * sizeof(std::int16_t);
          if (filled < max_bytes) {
            std::memset(reinterpret_cast<std::byte*>(pcm.data()) + filled, 0, max_bytes - filled);
          }
        } catch (...) {
          if (on_error_) on_error_("TX pump fill callback failed");
          std::memset(pcm.data(), 0, pcm.size() * sizeof(std::int16_t));
        }
      }

      if (on_tx_audio_) {
        on_tx_audio_(std::span<const std::int16_t>(pcm.data(), pcm.size()), sample_rate);
      }

      next_tick += std::chrono::duration_cast<std::chrono::steady_clock::duration>(frame_period);
      std::this_thread::sleep_until(next_tick);
    }
  }

  static constexpr int kDefaultRateHz = 11525;
  static constexpr std::size_t kDefaultFramesPerBuffer = 192;

  js8core::AudioStreamParams params_{};
  js8core::AudioOutputFill fill_;
  js8core::AudioErrorHandler on_error_;
  TxAudioHandler on_tx_audio_;
  std::mutex mutex_;
  bool running_ = false;
  std::thread worker_;
};

// Render a human-readable JS8 message if possible; otherwise return the raw frame
static bool is_callsign_like(std::string const& token) {
  if (token.size() < 3 || token.size() > 12) return false;
  if (!token.empty() && token.front() == '@') return false;
  bool has_digit = false;
  for (char c : token) {
    unsigned char uc = static_cast<unsigned char>(c);
    if (std::isdigit(uc)) {
      has_digit = true;
      continue;
    }
    if (std::isalpha(uc) || c == '/') continue;
    return false;
  }
  return has_digit;
}

static std::string maybe_insert_callsign_prefix(std::string const& text) {
  std::size_t first_sep = std::string::npos;
  for (std::size_t i = 0; i < text.size(); ++i) {
    if (std::isspace(static_cast<unsigned char>(text[i]))) {
      first_sep = i;
      break;
    }
  }
  if (first_sep == std::string::npos) return text;
  auto first_token = text.substr(0, first_sep);
  if (first_token.find(':') != std::string::npos) return text;

  std::vector<std::string> tokens;
  tokens.reserve(4);
  std::string current;
  for (char c : text) {
    if (std::isspace(static_cast<unsigned char>(c))) {
      if (!current.empty()) {
        tokens.push_back(current);
        current.clear();
        if (tokens.size() >= 2) break;
      }
      continue;
    }
    current.push_back(c);
  }
  if (!current.empty() && tokens.size() < 2) tokens.push_back(current);
  if (tokens.size() < 2) return text;

  if (!is_callsign_like(tokens[0]) || !is_callsign_like(tokens[1])) return text;

  std::string rebuilt;
  rebuilt.reserve(text.size() + 2);
  rebuilt = tokens[0] + ": " + text.substr(first_sep + 1);
  return rebuilt;
}

static std::string render_decoded_text(js8core::events::Decoded const& decoded) {
  using namespace js8core::protocol::varicode;

  auto const& frame = decoded.data;
  if (frame.size() < 12 || frame.find(' ') != std::string::npos) {
    return frame;
  }

  bool is_data_flag = (decoded.type & 0b100) == 0b100;
  // Try data payloads first (mirrors desktop unpack order).
  __android_log_print(ANDROID_LOG_DEBUG, "JS8FrameDebug",
                      "Trying data unpacker: frame='%s', decoded.type=0x%02x",
                      frame.c_str(), decoded.type);
  if (is_data_flag) {
    auto data = unpack_fast_data_message(frame);
    __android_log_print(ANDROID_LOG_DEBUG, "JS8FrameDebug",
                        "unpack_fast_data returned: '%s'", data.c_str());
    if (!data.empty()) return maybe_insert_callsign_prefix(data);
    // Fast-data frames should not be treated as heartbeat/compound/directed.
    __android_log_print(ANDROID_LOG_WARN, "JS8FrameDebug",
                        "Fast data unpack failed, returning raw frame: '%s'", frame.c_str());
    return frame;
  } else {
    auto data = unpack_data_message(frame);
    __android_log_print(ANDROID_LOG_DEBUG, "JS8FrameDebug",
                        "unpack_data returned: '%s'", data.c_str());
    if (!data.empty()) return maybe_insert_callsign_prefix(data);
  }

  // Heartbeat (most common for status beacons)
  std::uint8_t hb_type = 0;
  bool hb_alt = false;
  std::uint8_t hb_bits3 = 0;
  auto hb_parts = unpack_heartbeat_message(frame, &hb_type, &hb_alt, &hb_bits3);
  if (!hb_parts.empty()) {
    auto build_compound = [](std::vector<std::string> const& parts) {
      std::string a = parts.size() > 0 ? parts[0] : "";
      std::string b = parts.size() > 1 ? parts[1] : "";
      if (!a.empty() && !b.empty()) return a + "/" + b;
      return a.empty() ? b : a;
    };

    static const std::vector<std::string> kCqStrings = {
        "CQ CQ CQ", "CQ DX", "CQ QRP", "CQ CONTEST",
        "CQ FIELD", "CQ FD", "CQ CQ", "CQ"};

    auto callsign = build_compound(hb_parts);
    auto grid = hb_parts.size() > 2 ? hb_parts[2] : std::string{};

    std::string text = callsign;
    if (!text.empty()) text += ": ";

    if (hb_alt) {
      auto cq = hb_bits3 < kCqStrings.size() ? kCqStrings[hb_bits3] : std::string("CQ");
      text += "@ALLCALL " + cq;
    } else {
      text += "@HB HEARTBEAT";
    }
    if (!grid.empty()) {
      text += " " + grid;
    }
    return text;
  }

  // Compound (general CQ/MSG/grid/command) frames
  {
    std::uint8_t type = 0;
    std::uint16_t num = 0;
    std::uint8_t bits3 = 0;
    auto parts = unpack_compound_message(frame, &type, &num, &bits3);
    __android_log_print(ANDROID_LOG_DEBUG, "JS8FrameDebug",
                        "unpack_compound: frame='%s', type=%d, parts.size=%zu",
                        frame.c_str(), type, parts.size());
    if (!parts.empty()) {
      std::string out;
      for (std::size_t i = 0; i < parts.size(); ++i) {
        auto part = parts[i];
        // Trim leading spaces from tail parts to avoid double spaces
        while (!part.empty() && part.front() == ' ') part.erase(part.begin());
        if (!part.empty()) {
          if (!out.empty()) out += " ";
          out += part;
        }
      }
      if (!out.empty()) {
        __android_log_print(ANDROID_LOG_WARN, "JS8FrameDebug",
                            "Returning compound result: '%s' (should this be data?)", out.c_str());
        return out;
      }
    }
  }

  // Directed frames (commands, directed messages)
  {
    std::uint8_t type = 0;
    auto parts = unpack_directed_message(frame, &type);
    if (!parts.empty()) {
      std::vector<std::string> tokens;
      tokens.reserve(parts.size());
      for (auto part : parts) {
        while (!part.empty() && part.front() == ' ') part.erase(part.begin());
        if (!part.empty()) tokens.push_back(part);
      }
      if (!tokens.empty()) {
        std::string out;
        if (tokens.size() >= 2) {
          out = tokens[0] + ": " + tokens[1];
          for (std::size_t i = 2; i < tokens.size(); ++i) {
            out += " " + tokens[i];
          }
        } else {
          out = tokens[0];
        }
        if (!out.empty()) return out;
      }
    }
  }

  // Last resort: return raw frame
  __android_log_print(ANDROID_LOG_WARN, "JS8FrameDebug",
                      "All unpackers failed, returning raw frame: '%s'", frame.c_str());
  return frame;
}

// Event callback that marshals C++ events to Java
static void event_callback(JS8Engine_Native* native, js8core::events::Variant const& event) {
  if (!native || !native->callback_handler) return;

  JNIEnv* env = get_jni_env();
  if (!env) return;

  std::lock_guard<std::mutex> lock(native->callback_mutex);

  // Get callback handler class
  jclass handler_class = env->GetObjectClass(native->callback_handler);
  if (!handler_class) return;

  // Handle different event types
  if (auto* decoded = std::get_if<js8core::events::Decoded>(&event)) {
    auto rendered = render_decoded_text(*decoded);

    auto emit_decoded = [&](js8core::events::Decoded const& d, std::string const& text_str) {
      jmethodID method = env->GetMethodID(handler_class, "onDecoded", "(IIFFLjava/lang/String;IFI)V");
      if (method) {
        jstring text = env->NewStringUTF(text_str.c_str());
        env->CallVoidMethod(native->callback_handler, method,
                           d.utc, d.snr, d.xdt, d.frequency,
                           text, d.type, d.quality, d.mode);
        env->DeleteLocalRef(text);
      }
    };
    emit_decoded(*decoded, rendered);
  } else if (auto* spectrum = std::get_if<js8core::events::Spectrum>(&event)) {
    // Call onSpectrum(float[] bins, float binHz, float powerDb, float peakDb)
    jmethodID method = env->GetMethodID(handler_class, "onSpectrum", "([FFFF)V");
    if (method) {
      jfloatArray bins = env->NewFloatArray(static_cast<jsize>(spectrum->bins.size()));
      env->SetFloatArrayRegion(bins, 0, static_cast<jsize>(spectrum->bins.size()),
                               spectrum->bins.data());
      env->CallVoidMethod(native->callback_handler, method,
                         bins, spectrum->bin_hz, spectrum->power_db, spectrum->peak_db);
      env->DeleteLocalRef(bins);
    }
  } else if (auto* decode_started = std::get_if<js8core::events::DecodeStarted>(&event)) {
    // Call onDecodeStarted(int submodes)
    jmethodID method = env->GetMethodID(handler_class, "onDecodeStarted", "(I)V");
    if (method) {
      env->CallVoidMethod(native->callback_handler, method, decode_started->submodes);
    }
  } else if (auto* decode_finished = std::get_if<js8core::events::DecodeFinished>(&event)) {
    // Call onDecodeFinished(int count)
    jmethodID method = env->GetMethodID(handler_class, "onDecodeFinished", "(I)V");
    if (method) {
      env->CallVoidMethod(native->callback_handler, method,
                         static_cast<jint>(decode_finished->decoded));
    }
  }

  env->DeleteLocalRef(handler_class);
}

// Error callback
static void error_callback(JS8Engine_Native* native, std::string_view message) {
  if (!native || !native->callback_handler) return;

  JNIEnv* env = get_jni_env();
  if (!env) return;

  std::lock_guard<std::mutex> lock(native->callback_mutex);

  jclass handler_class = env->GetObjectClass(native->callback_handler);
  if (!handler_class) return;

  jmethodID method = env->GetMethodID(handler_class, "onError", "(Ljava/lang/String;)V");
  if (method) {
    jstring msg = env->NewStringUTF(std::string(message).c_str());
    env->CallVoidMethod(native->callback_handler, method, msg);
    env->DeleteLocalRef(msg);
  }

  env->DeleteLocalRef(handler_class);
}

// Log callback
static void log_callback(JS8Engine_Native* native, js8core::LogLevel level, std::string_view message) {
  if (!native || !native->callback_handler) return;

  JNIEnv* env = get_jni_env();
  if (!env) return;

  std::lock_guard<std::mutex> lock(native->callback_mutex);

  jclass handler_class = env->GetObjectClass(native->callback_handler);
  if (!handler_class) return;

  jmethodID method = env->GetMethodID(handler_class, "onLog", "(ILjava/lang/String;)V");
  if (method) {
    jstring msg = env->NewStringUTF(std::string(message).c_str());
    env->CallVoidMethod(native->callback_handler, method, static_cast<jint>(level), msg);
    env->DeleteLocalRef(msg);
  }

  env->DeleteLocalRef(handler_class);
}

extern "C" {

JS8Engine_Native* js8_engine_create(JNIEnv* env, jobject callback_handler,
                                     int sample_rate_hz, int submodes,
                                     int enable_tx_audio_tap) {
  if (!env || !callback_handler) return nullptr;

  auto native = new JS8Engine_Native();

  // Create global reference to callback handler
  native->callback_handler = env->NewGlobalRef(callback_handler);

  // Create adapters
  native->logger = std::make_unique<js8core::android::AndroidLogger>("JS8Call");

  // Get app files directory for storage (should be passed from Java, using temp for now)
  native->storage = std::make_unique<js8core::android::FileStorage>("/data/local/tmp/js8call");

  native->scheduler = std::make_unique<js8core::android::ThreadScheduler>();
  // Capture is driven from JS8AudioHelper (Java) with explicit resampling; disable native Oboe capture
  native->audio_in = nullptr;
  if (enable_tx_audio_tap != 0) {
    native->audio_out = std::make_unique<PumpAudioOutput>(
        [native](std::span<const std::int16_t> samples, int sample_rate_hz) {
          if (!native || !native->callback_handler || samples.empty()) return;
          JNIEnv* cb_env = get_jni_env();
          if (!cb_env) return;
          std::lock_guard<std::mutex> cb_lock(native->callback_mutex);
          jclass handler_class = cb_env->GetObjectClass(native->callback_handler);
          if (!handler_class) return;
          jmethodID method = cb_env->GetMethodID(handler_class, "onTxAudio", "([SI)V");
          if (method) {
            auto array = cb_env->NewShortArray(static_cast<jsize>(samples.size()));
            if (array) {
              cb_env->SetShortArrayRegion(array,
                                          0,
                                          static_cast<jsize>(samples.size()),
                                          reinterpret_cast<const jshort*>(samples.data()));
              cb_env->CallVoidMethod(native->callback_handler, method, array,
                                     static_cast<jint>(sample_rate_hz));
              cb_env->DeleteLocalRef(array);
            }
          }
          cb_env->DeleteLocalRef(handler_class);
        });
  } else {
    native->audio_out = std::make_unique<js8core::android::OboeAudioOutput>();
  }
  native->udp = std::make_unique<js8core::android::BsdUdpChannel>();
  native->rig = std::make_unique<js8core::android::NullRigControl>();

  // Configure engine
  js8core::EngineConfig config;
  config.sample_rate_hz = sample_rate_hz;
  config.submodes = (submodes == 0) ? 0x1F : submodes;  // default to all standard submodes
  config.tx_output_rate_hz = (enable_tx_audio_tap != 0) ? 11520 : 0;
  // TruSDX serial path (tx_audio_tap): raise gain so the 8-bit serial audio
  // uses a meaningful portion of the U8 range (~128 of 256 levels at 0.5).
  // Too low (0.2) wastes resolution and sounds harsh; too high (1.0) overdrives
  // the radio's TX chain producing a noisy carrier with no modulation.
  // Oboe/speaker path: keep headroom to avoid splatter/ALC on the analog chain.
  config.tx_output_gain = (enable_tx_audio_tap != 0) ? 0.5f : 0.2f;

  js8core::EngineCallbacks callbacks;
  callbacks.on_event = [native](js8core::events::Variant const& event) {
    // Log event types for debugging
    if (std::holds_alternative<js8core::events::DecodeStarted>(event)) {
      auto const& e = std::get<js8core::events::DecodeStarted>(event);
      __android_log_print(ANDROID_LOG_DEBUG, "JS8Engine_Native",
                         "DecodeStarted: submodes=%d", e.submodes);
    } else if (std::holds_alternative<js8core::events::DecodeFinished>(event)) {
      auto const& e = std::get<js8core::events::DecodeFinished>(event);
      __android_log_print(ANDROID_LOG_DEBUG, "JS8Engine_Native",
                         "DecodeFinished: count=%zu", e.decoded);
    } else if (std::holds_alternative<js8core::events::Decoded>(event)) {
      auto const& e = std::get<js8core::events::Decoded>(event);
      auto rendered = render_decoded_text(e);
      __android_log_print(ANDROID_LOG_INFO, "JS8Engine_Native",
                         "DECODED: SNR=%d dB, freq=%.1f Hz, text='%s', raw='%s', type=%d, mode=%d",
                         e.snr, e.frequency, rendered.c_str(), e.data.c_str(), e.type, e.mode);
    }
    event_callback(native, event);
  };
  callbacks.on_error = [native](std::string_view msg) {
    error_callback(native, msg);
  };
  callbacks.on_log = [native](js8core::LogLevel level, std::string_view msg) {
    // Also log to Android logcat for debugging
    int android_level = ANDROID_LOG_DEBUG;
    switch (level) {
      case js8core::LogLevel::Error: android_level = ANDROID_LOG_ERROR; break;
      case js8core::LogLevel::Warn: android_level = ANDROID_LOG_WARN; break;
      case js8core::LogLevel::Info: android_level = ANDROID_LOG_INFO; break;
      case js8core::LogLevel::Debug: android_level = ANDROID_LOG_DEBUG; break;
      case js8core::LogLevel::Trace: android_level = ANDROID_LOG_VERBOSE; break;
    }
    __android_log_print(android_level, "JS8Core",
                       "%.*s", static_cast<int>(msg.length()), msg.data());
    log_callback(native, level, msg);
  };

  js8core::EngineDependencies deps;
  deps.audio_in = nullptr;  // Use Java-side capture instead of native Oboe
  deps.audio_out = native->audio_out.get();
  deps.rig = native->rig.get();
  deps.scheduler = native->scheduler.get();
  deps.storage = native->storage.get();
  deps.logger = native->logger.get();
  deps.udp = native->udp.get();

  // Create engine
  native->engine = js8core::make_engine(config, std::move(callbacks), deps);

  // Set up audio format
  native->audio_format.sample_rate = sample_rate_hz;
  native->audio_format.channels = 1;  // Mono
  native->audio_format.sample_type = js8core::SampleType::Int16;

  return native;
}

void js8_engine_destroy(JS8Engine_Native* engine) {
  if (!engine) return;

  // Stop engine first
  js8_engine_stop(engine);

  // Destroy the core engine while its callbacks and adapter dependencies are
  // still alive. Its destructor stops and joins the decode/spectrum workers.
  engine->engine.reset();

  // Delete global reference to callback handler
  if (engine->callback_handler) {
    JNIEnv* env = get_jni_env();
    if (env) {
      env->DeleteGlobalRef(engine->callback_handler);
    }
    engine->callback_handler = nullptr;
  }

  delete engine;
}

int js8_engine_start(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return 0;
  return engine->engine->start() ? 1 : 0;
}

void js8_engine_stop(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return;
  engine->engine->stop();
}

int js8_engine_submit_audio(JS8Engine_Native* engine, const int16_t* samples,
                            size_t num_samples, int64_t timestamp_ns) {
  if (!engine || !engine->engine || !samples) return 0;
  if (num_samples > std::numeric_limits<size_t>::max() / sizeof(int16_t)) return 0;

  // Convert to byte span
  size_t byte_size = num_samples * sizeof(int16_t);
  const std::byte* byte_data = reinterpret_cast<const std::byte*>(samples);
  std::span<const std::byte> data_span(byte_data, byte_size);

  // Create audio buffer
  js8core::AudioInputBuffer buffer;
  buffer.data = data_span;
  buffer.format = engine->audio_format;
  buffer.captured_at = js8core::SteadyClock::now();

  bool result = engine->engine->submit_capture(buffer);

  // Log occasionally for debugging
  static int submit_count = 0;
  if ((submit_count++ % 500) == 0) {
    __android_log_print(ANDROID_LOG_DEBUG, "JS8Engine_Native",
                       "Audio submit #%d: %zu samples, result=%d, sample_rate=%d",
                       submit_count, num_samples, result, engine->audio_format.sample_rate);
  }

  return result ? 1 : 0;
}

// Decimate raw input to the engine sample rate using the same FIR as native OboeAudioInput.
int js8_engine_submit_audio_raw(JS8Engine_Native* engine, const int16_t* samples,
                                size_t num_samples, int input_sample_rate,
                                int64_t timestamp_ns) {
  if (!engine || !engine->engine || !samples) return 0;
  if (input_sample_rate <= 0) return 0;

  int target_rate = engine->audio_format.sample_rate;
  if (target_rate <= 0) return 0;

  if (input_sample_rate % target_rate != 0) {
    if (engine->resample_input_rate != input_sample_rate ||
        engine->resample_output_rate != target_rate) {
      engine->resample_input_rate = input_sample_rate;
      engine->resample_output_rate = target_rate;
      engine->resample_buffer.clear();
      engine->resample_pos = 0.0;
      __android_log_print(ANDROID_LOG_INFO, "JS8Engine_Native",
                         "Fractional resampler configured: input_rate=%d, target_rate=%d",
                         input_sample_rate, target_rate);
    }

    engine->resample_buffer.reserve(engine->resample_buffer.size() + num_samples);
    for (size_t i = 0; i < num_samples; ++i) {
      engine->resample_buffer.push_back(static_cast<float>(samples[i]));
    }

    double step = static_cast<double>(input_sample_rate) / static_cast<double>(target_rate);
    if (step <= 0.0) return 0;

    std::vector<int16_t> resampled;
    if (engine->resample_buffer.size() > 1) {
      double available = static_cast<double>(engine->resample_buffer.size() - 1) - engine->resample_pos;
      if (available > 0) {
        std::size_t estimate = static_cast<std::size_t>(available / step) + 1;
        resampled.reserve(estimate);
        while (engine->resample_pos + 1.0 < engine->resample_buffer.size()) {
          std::size_t idx = static_cast<std::size_t>(engine->resample_pos);
          double frac = engine->resample_pos - static_cast<double>(idx);
          float a = engine->resample_buffer[idx];
          float b = engine->resample_buffer[idx + 1];
          float sample = a + static_cast<float>(frac) * (b - a);
          int value = static_cast<int>(std::lrint(sample));
          value = std::clamp(value,
                             static_cast<int>(std::numeric_limits<int16_t>::min()),
                             static_cast<int>(std::numeric_limits<int16_t>::max()));
          resampled.push_back(static_cast<int16_t>(value));
          engine->resample_pos += step;
        }
      }
    }

    if (!resampled.empty()) {
      std::size_t drop = static_cast<std::size_t>(engine->resample_pos);
      if (drop > 0) {
        if (drop >= engine->resample_buffer.size()) {
          engine->resample_buffer.clear();
          engine->resample_pos = 0.0;
        } else {
          engine->resample_buffer.erase(
              engine->resample_buffer.begin(),
              engine->resample_buffer.begin() +
                  static_cast<std::vector<float>::difference_type>(drop));
          engine->resample_pos -= static_cast<double>(drop);
        }
      }
      return js8_engine_submit_audio(engine, resampled.data(), resampled.size(), timestamp_ns);
    }

    return 0;
  }

  int factor = input_sample_rate / target_rate;

  // Rebuild taps/buffer if factor changed or not initialized.
  if (factor != engine->decimation_factor || engine->decimation_taps.empty()) {
    engine->decimation_factor = factor;
    engine->decimation_taps = js8core::dsp::make_js8_fir(input_sample_rate, target_rate);
    engine->decimation_mirror = static_cast<int>(engine->decimation_taps.size());
    engine->decimation_buffer.assign(engine->decimation_mirror * 2, 0);
    engine->decimation_pos = 0;
    __android_log_print(ANDROID_LOG_INFO, "JS8Engine_Native",
                       "Decimator configured: input_rate=%d, target_rate=%d, factor=%d, taps=%zu",
                       input_sample_rate, target_rate, factor, engine->decimation_taps.size());
    engine->resample_buffer.clear();
    engine->resample_pos = 0.0;
    engine->resample_input_rate = 0;
    engine->resample_output_rate = 0;
  }

  std::vector<int16_t> decimated;
  if (factor <= 1) {
    decimated.assign(samples, samples + num_samples);
  } else {
    decimated.reserve(num_samples / static_cast<size_t>(factor) + 1);
    int taps = static_cast<int>(engine->decimation_taps.size());
    int mirror = engine->decimation_mirror;

    for (size_t i = 0; i < num_samples; ++i) {
      int16_t sample = samples[i];
      engine->decimation_buffer[engine->decimation_pos] = sample;
      engine->decimation_buffer[engine->decimation_pos + mirror] = sample;
      engine->decimation_pos = (engine->decimation_pos + 1) % mirror;

      if ((static_cast<int>(i) % factor) == factor - 1) {
        double acc = 0.0;
        int read_pos = (engine->decimation_pos - 1 + mirror) % mirror;
        for (int j = 0; j < taps; ++j) {
          int idx = (read_pos - j + mirror) % mirror;
          acc += static_cast<double>(engine->decimation_taps[j]) *
                 static_cast<double>(engine->decimation_buffer[idx]);
        }
        int value = static_cast<int>(std::lrint(acc));
        value = std::clamp(value,
                           static_cast<int>(std::numeric_limits<int16_t>::min()),
                           static_cast<int>(std::numeric_limits<int16_t>::max()));
        decimated.push_back(static_cast<int16_t>(value));
      }
    }
  }

  return js8_engine_submit_audio(engine, decimated.data(), decimated.size(), timestamp_ns);
}

void js8_engine_set_frequency(JS8Engine_Native* engine, uint64_t frequency_hz) {
  // TODO: Implement frequency setting through engine API
  (void)engine;
  (void)frequency_hz;
}

void js8_engine_set_submodes(JS8Engine_Native* engine, int submodes) {
  // TODO: Implement submode setting through engine API
  (void)engine;
  (void)submodes;
}

void js8_engine_set_output_device(JS8Engine_Native* engine, int device_id) {
  if (!engine || !engine->audio_out) return;
  auto* oboe_out = dynamic_cast<js8core::android::OboeAudioOutput*>(engine->audio_out.get());
  if (!oboe_out) return;
  oboe_out->set_device_id(device_id);
}

void js8_engine_set_tx_boost_enabled(JS8Engine_Native* engine, bool enabled) {
  if (!engine || !engine->engine) return;
  std::lock_guard<std::mutex> lock(engine->tx_boost_mutex);
  engine->tx_boost_enabled = enabled;
  engine->engine->set_tx_boost_enabled(enabled);
}

int js8_engine_transmit_message(JS8Engine_Native* engine,
                                const char* text,
                                const char* my_call,
                                const char* my_grid,
                                const char* selected_call,
                                int submode,
                                double audio_frequency_hz,
                                double tx_delay_s,
                                int force_identify,
                                int force_data) {
  if (!engine || !engine->engine) return 0;

  js8core::TxMessageRequest request;
  request.text = text ? text : "";
  request.my_call = my_call ? my_call : "";
  request.my_grid = my_grid ? my_grid : "";
  request.selected_call = selected_call ? selected_call : "";
  request.submode = submode;
  request.audio_frequency_hz = audio_frequency_hz;
  request.tx_delay_s = tx_delay_s;
  request.force_identify = force_identify != 0;
  request.force_data = force_data != 0;

  return engine->engine->transmit_message(request) ? 1 : 0;
}

int js8_engine_transmit_frame(JS8Engine_Native* engine,
                              const char* frame,
                              int bits,
                              int submode,
                              double audio_frequency_hz,
                              double tx_delay_s) {
  if (!engine || !engine->engine || !frame) return 0;

  js8core::TxFrameRequest request;
  request.frame = frame;
  request.bits = bits;
  request.submode = submode;
  request.audio_frequency_hz = audio_frequency_hz;
  request.tx_delay_s = tx_delay_s;

  return engine->engine->transmit_frame(request) ? 1 : 0;
}

int js8_engine_start_tune(JS8Engine_Native* engine,
                          double audio_frequency_hz,
                          int submode,
                          double tx_delay_s) {
  if (!engine || !engine->engine) return 0;
  return engine->engine->start_tune(audio_frequency_hz, submode, tx_delay_s) ? 1 : 0;
}

void js8_engine_stop_transmit(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return;
  engine->engine->stop_transmit();
}

int js8_engine_is_transmitting(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return 0;
  return engine->engine->is_transmitting() ? 1 : 0;
}

int js8_engine_is_transmitting_audio(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return 0;
  return engine->engine->is_transmitting_audio() ? 1 : 0;
}

int js8_engine_tx_milliseconds_until_audio(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return -1;
  return engine->engine->tx_milliseconds_until_audio();
}

void js8_engine_set_tx_ready(JS8Engine_Native* engine, bool ready) {
  if (!engine || !engine->engine) return;
  engine->engine->set_tx_ready(ready);
}

int js8_engine_is_running(JS8Engine_Native* engine) {
  if (!engine || !engine->engine) return 0;
  // TODO: Add is_running() method to engine interface
  return 1;  // Assume running if engine exists
}

int js8_register_natives(JavaVM* vm, JNIEnv* env) {
  g_jvm = vm;

  // Register native methods for JS8Engine
  jclass js8_engine_class = env->FindClass("com/js8call/core/JS8Engine");
  if (!js8_engine_class) {
    return 0;
  }

  // Map Kotlin Companion method to static method
  JNINativeMethod methods[] = {
    {"nativeCreate", "(Lcom/js8call/core/JS8Engine$CallbackHandler;IIZ)J",
     (void*)Java_com_js8call_core_JS8Engine_00024Companion_nativeCreate},
    {"nativeStart", "(J)Z", (void*)Java_com_js8call_core_JS8Engine_nativeStart},
    {"nativeStop", "(J)V", (void*)Java_com_js8call_core_JS8Engine_nativeStop},
    {"nativeDestroy", "(J)V", (void*)Java_com_js8call_core_JS8Engine_nativeDestroy},
    {"nativeSubmitAudio", "(J[SIJ)Z", (void*)Java_com_js8call_core_JS8Engine_nativeSubmitAudio},
    {"nativeSubmitAudioRaw", "(J[SIIJ)Z", (void*)Java_com_js8call_core_JS8Engine_nativeSubmitAudioRaw},
    {"nativeSetFrequency", "(JJ)V", (void*)Java_com_js8call_core_JS8Engine_nativeSetFrequency},
    {"nativeSetSubmodes", "(JI)V", (void*)Java_com_js8call_core_JS8Engine_nativeSetSubmodes},
    {"nativeSetOutputDevice", "(JI)V", (void*)Java_com_js8call_core_JS8Engine_nativeSetOutputDevice},
    {"nativeSetTxBoostEnabled", "(JZ)V", (void*)Java_com_js8call_core_JS8Engine_nativeSetTxBoostEnabled},
    {"nativeTransmitMessage", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IDDZZ)Z",
     (void*)Java_com_js8call_core_JS8Engine_nativeTransmitMessage},
    {"nativeTransmitFrame", "(JLjava/lang/String;IIDD)Z",
     (void*)Java_com_js8call_core_JS8Engine_nativeTransmitFrame},
    {"nativeStartTune", "(JDID)Z",
     (void*)Java_com_js8call_core_JS8Engine_nativeStartTune},
    {"nativeStopTransmit", "(J)V",
     (void*)Java_com_js8call_core_JS8Engine_nativeStopTransmit},
    {"nativeIsTransmitting", "(J)Z",
     (void*)Java_com_js8call_core_JS8Engine_nativeIsTransmitting},
    {"nativeIsTransmittingAudio", "(J)Z",
      (void*)Java_com_js8call_core_JS8Engine_nativeIsTransmittingAudio},
    {"nativeTxMillisecondsUntilAudio", "(J)I",
     (void*)Java_com_js8call_core_JS8Engine_nativeTxMillisecondsUntilAudio},
    {"nativeSetTxReady", "(JZ)V",
     (void*)Java_com_js8call_core_JS8Engine_nativeSetTxReady},
    {"nativeIsRunning", "(J)Z", (void*)Java_com_js8call_core_JS8Engine_nativeIsRunning}
  };

  int num_methods = sizeof(methods) / sizeof(methods[0]);
  if (env->RegisterNatives(js8_engine_class, methods, num_methods) != JNI_OK) {
    return 0;
  }

  env->DeleteLocalRef(js8_engine_class);

  return 1;
}

}  // extern "C"

// JNI_OnLoad - called when library is loaded
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)reserved;

  JNIEnv* env = nullptr;
  if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  js8_register_natives(vm, env);

  return JNI_VERSION_1_6;
}
