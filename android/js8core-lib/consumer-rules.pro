# Consumer ProGuard rules for JS8Call Core Library

# Keep all public API classes
-keep public class com.js8call.core.** { public *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep callback interface implementations
-keep interface com.js8call.core.JS8Engine$CallbackHandler { *; }
-keep class * implements com.js8call.core.JS8Engine$CallbackHandler { *; }

# Keep JNI callback methods that are called from native code
-keepclassmembers class com.js8call.core.JS8Engine$CallbackHandler {
    public void onDecoded(...);
    public void onSpectrum(...);
    public void onDecodeStarted(...);
    public void onDecodeFinished(...);
    public void onError(...);
    public void onLog(...);
}

# Keep Companion object for static factory methods
-keepclassmembers class com.js8call.core.JS8Engine$Companion {
    public ** create(...);
}

# Oboe library
-keep class com.google.oboe.** { *; }

# Don't warn about missing classes (native deps)
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
