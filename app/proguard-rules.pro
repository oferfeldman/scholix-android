# Debug info
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all annotations
-keepattributes *Annotation*

# Kotlin and serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.atomicfu.**

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

## OkHttp / Okio
#-dontwarn okhttp3.**
#-dontwarn okio.**
#-dontwarn javax.annotation.**

# Compose
-keep class androidx.compose.** { *; }

# Firebase / Google Play
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

## Keep OkHttp for Ktor (critical)
#-keep class okhttp3.** { *; }
#-keep interface okhttp3.** { *; }

# Keep Okio
-keep class okio.** { *; }

# Keep all Scholix classes
-keep class com.feldman.scholix.** { *; }
-dontwarn com.feldman.scholix.**

-keep class com.feldman.app.api.** { *; }
-keep class com.feldman.scholix.api.platforms.** { *; }
-keep class com.feldman.scholix.api.** { *; }

-keep class * implements com.feldman.scholix.api.Platform {
    *;
}
