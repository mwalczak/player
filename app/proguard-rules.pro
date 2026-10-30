# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.mwalczak.player.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mwalczak.player.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
