# JS bridges are called reflectively from page scripts.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Zipline/QuickJS JNI entry points.
-keep class app.cash.zipline.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.webdroid.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.webdroid.app.**$$serializer { *; }

# OkHttp / Jsoup optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
