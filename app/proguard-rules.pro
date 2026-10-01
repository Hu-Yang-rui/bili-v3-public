# Moshi / Retrofit
-keepclassmembers class ** {
    @com.squareup.moshi.JsonQualifier <fields>;
}
-keep,allowobfuscation @interface com.squareup.moshi.JsonQualifier
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

# 数据模型（Moshi 反射需要）
-keep class com.example.biliv3.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Media3
-dontwarn androidx.media3.**
