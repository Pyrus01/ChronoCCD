# Keep Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * {
    <fields>;
}

# Keep ChronoCCD models & ISP data classes
-keep class com.chronoccd.studio.data.** { *; }
-keep class com.chronoccd.studio.isp.** { *; }

# Compose & MaterialKolor rules
-keep class com.materialkolor.** { *; }
-dontwarn com.materialkolor.**

# Health Connect SDK
-keep class androidx.health.connect.** { *; }
-dontwarn androidx.health.connect.**
