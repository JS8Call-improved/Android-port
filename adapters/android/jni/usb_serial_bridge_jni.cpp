#include "usb_serial_bridge.hpp"

#include <jni.h>
#if defined(__ANDROID__)
#include <android/log.h>
#endif

#include <mutex>

namespace {

struct BridgeState {
  JavaVM* jvm = nullptr;
  jobject bridge = nullptr;
  jmethodID open = nullptr;
  jmethodID read = nullptr;
  jmethodID write = nullptr;
  jmethodID close = nullptr;
  jmethodID set_rts = nullptr;
  jmethodID set_dtr = nullptr;
  jmethodID purge = nullptr;
  std::mutex mutex;
};

BridgeState g_bridge;

struct BridgeSnapshot {
  JavaVM* jvm = nullptr;
  jobject bridge = nullptr;
  jmethodID open = nullptr;
  jmethodID read = nullptr;
  jmethodID write = nullptr;
  jmethodID close = nullptr;
  jmethodID set_rts = nullptr;
  jmethodID set_dtr = nullptr;
  jmethodID purge = nullptr;
};

JNIEnv* get_env(JavaVM* jvm) {
  if (!jvm) return nullptr;
  JNIEnv* env = nullptr;
  int status = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (status == JNI_EDETACHED) {
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
      return nullptr;
    }
  }
  return env;
}

bool snapshot_bridge(BridgeSnapshot& out) {
  std::lock_guard<std::mutex> lock(g_bridge.mutex);
  if (!g_bridge.bridge || !g_bridge.open || !g_bridge.read || !g_bridge.write || !g_bridge.close) {
    return false;
  }
  out.jvm = g_bridge.jvm;
  out.bridge = g_bridge.bridge;
  out.open = g_bridge.open;
  out.read = g_bridge.read;
  out.write = g_bridge.write;
  out.close = g_bridge.close;
  out.set_rts = g_bridge.set_rts;
  out.set_dtr = g_bridge.set_dtr;
  out.purge = g_bridge.purge;
  return true;
}

void log_exception(const char* where) {
#if defined(__ANDROID__)
  __android_log_print(ANDROID_LOG_WARN, "UsbSerialBridgeJNI", "JNI exception at %s", where);
#else
  (void)where;
#endif
}

bool clear_exception(JNIEnv* env, const char* where) {
  if (!env->ExceptionCheck()) return true;
  log_exception(where);
  env->ExceptionClear();
  return false;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_js8call_core_UsbSerialBridge_nativeRegister(JNIEnv* env, jobject thiz) {
  std::lock_guard<std::mutex> lock(g_bridge.mutex);

  if (g_bridge.bridge) {
    env->DeleteGlobalRef(g_bridge.bridge);
    g_bridge.bridge = nullptr;
  }

  env->GetJavaVM(&g_bridge.jvm);
  g_bridge.bridge = env->NewGlobalRef(thiz);

  jclass cls = env->GetObjectClass(thiz);
  g_bridge.open = env->GetMethodID(cls, "open", "(IIIIII)Z");
  g_bridge.read = env->GetMethodID(cls, "read", "([BI)I");
  g_bridge.write = env->GetMethodID(cls, "write", "([BII)I");
  g_bridge.close = env->GetMethodID(cls, "close", "()V");
  g_bridge.set_rts = env->GetMethodID(cls, "setRts", "(Z)Z");
  g_bridge.set_dtr = env->GetMethodID(cls, "setDtr", "(Z)Z");
  g_bridge.purge = env->GetMethodID(cls, "purge", "()Z");
  env->DeleteLocalRef(cls);
}

extern "C" JNIEXPORT void JNICALL
Java_com_js8call_core_UsbSerialBridge_nativeUnregister(JNIEnv* env, jobject thiz) {
  std::lock_guard<std::mutex> lock(g_bridge.mutex);
  // A stale bridge must not clear a newer one's registration
  if (!g_bridge.bridge || !env->IsSameObject(g_bridge.bridge, thiz)) return;
  env->DeleteGlobalRef(g_bridge.bridge);
  g_bridge.bridge = nullptr;
  g_bridge.open = nullptr;
  g_bridge.read = nullptr;
  g_bridge.write = nullptr;
  g_bridge.close = nullptr;
  g_bridge.set_rts = nullptr;
  g_bridge.set_dtr = nullptr;
  g_bridge.purge = nullptr;
}

namespace js8core::android {

bool usb_serial_bridge_ready() {
  std::lock_guard<std::mutex> lock(g_bridge.mutex);
  return g_bridge.bridge && g_bridge.open && g_bridge.read && g_bridge.write && g_bridge.close;
}

bool usb_serial_open(int device_id,
                     int port_index,
                     int baud_rate,
                     int data_bits,
                     int stop_bits,
                     int parity) {
  BridgeSnapshot state;
  if (!snapshot_bridge(state)) return false;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return false;

  jboolean ok = env->CallBooleanMethod(state.bridge, state.open, device_id, port_index,
                                       baud_rate, data_bits, stop_bits, parity);
  if (!clear_exception(env, "open")) return false;
  return ok == JNI_TRUE;
}

int usb_serial_read(std::uint8_t* buffer, std::size_t length, int timeout_ms) {
  if (!buffer || length == 0) return 0;

  BridgeSnapshot state;
  if (!snapshot_bridge(state)) return -1;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return -1;

  jsize len = static_cast<jsize>(length);
  jbyteArray array = env->NewByteArray(len);
  if (!array) return -1;

  jint read = env->CallIntMethod(state.bridge, state.read, array, timeout_ms);
  if (!clear_exception(env, "read")) {
    env->DeleteLocalRef(array);
    return -1;
  }

  if (read > 0) {
    env->GetByteArrayRegion(array, 0, read, reinterpret_cast<jbyte*>(buffer));
  }

  env->DeleteLocalRef(array);
  return static_cast<int>(read);
}

int usb_serial_write(const std::uint8_t* buffer, std::size_t length, int timeout_ms) {
  if (!buffer || length == 0) return 0;

  BridgeSnapshot state;
  if (!snapshot_bridge(state)) return -1;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return -1;

  jsize len = static_cast<jsize>(length);
  jbyteArray array = env->NewByteArray(len);
  if (!array) return -1;
  env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte*>(buffer));

  jint written = env->CallIntMethod(state.bridge, state.write, array, len, timeout_ms);
  if (!clear_exception(env, "write")) {
    env->DeleteLocalRef(array);
    return -1;
  }

  env->DeleteLocalRef(array);
  return static_cast<int>(written);
}

void usb_serial_close() {
  BridgeSnapshot state;
  if (!snapshot_bridge(state)) return;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return;

  env->CallVoidMethod(state.bridge, state.close);
  clear_exception(env, "close");
}

int usb_serial_set_rts(bool enabled) {
  BridgeSnapshot state;
  if (!snapshot_bridge(state) || !state.set_rts) return -1;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return -1;

  jboolean ok = env->CallBooleanMethod(state.bridge, state.set_rts, enabled ? JNI_TRUE : JNI_FALSE);
  if (!clear_exception(env, "setRts")) return -1;
  return ok == JNI_TRUE ? 0 : -1;
}

int usb_serial_set_dtr(bool enabled) {
  BridgeSnapshot state;
  if (!snapshot_bridge(state) || !state.set_dtr) return -1;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return -1;

  jboolean ok = env->CallBooleanMethod(state.bridge, state.set_dtr, enabled ? JNI_TRUE : JNI_FALSE);
  if (!clear_exception(env, "setDtr")) return -1;
  return ok == JNI_TRUE ? 0 : -1;
}

int usb_serial_flush() {
  BridgeSnapshot state;
  if (!snapshot_bridge(state) || !state.purge) return -1;

  JNIEnv* env = get_env(state.jvm);
  if (!env) return -1;

  jboolean ok = env->CallBooleanMethod(state.bridge, state.purge);
  if (!clear_exception(env, "purge")) return -1;
  return ok == JNI_TRUE ? 0 : -1;
}

}  // namespace js8core::android

extern "C" int js8_android_usb_serial_is_ready() {
  return js8core::android::usb_serial_bridge_ready() ? 1 : 0;
}

extern "C" int js8_android_usb_serial_open(int device_id,
                                           int port_index,
                                           int baud_rate,
                                           int data_bits,
                                           int stop_bits,
                                           int parity) {
  return js8core::android::usb_serial_open(device_id, port_index, baud_rate,
                                           data_bits, stop_bits, parity)
             ? 0
             : -1;
}

extern "C" int js8_android_usb_serial_read(unsigned char* buffer,
                                           unsigned long length,
                                           int timeout_ms) {
  return js8core::android::usb_serial_read(buffer, static_cast<std::size_t>(length), timeout_ms);
}

extern "C" int js8_android_usb_serial_write(const unsigned char* buffer,
                                            unsigned long length,
                                            int timeout_ms) {
  return js8core::android::usb_serial_write(buffer, static_cast<std::size_t>(length), timeout_ms);
}

extern "C" int js8_android_usb_serial_set_rts(int state) {
  return js8core::android::usb_serial_set_rts(state != 0);
}

extern "C" int js8_android_usb_serial_set_dtr(int state) {
  return js8core::android::usb_serial_set_dtr(state != 0);
}

extern "C" int js8_android_usb_serial_flush() {
  return js8core::android::usb_serial_flush();
}

extern "C" int js8_android_usb_serial_close() {
  js8core::android::usb_serial_close();
  return 0;
}
