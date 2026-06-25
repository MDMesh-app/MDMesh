# Manifest entry points (provisioning / kiosk launcher) — kept explicitly.
-keep class com.lunacy.mdm.agent.admin.AdminReceiver { *; }
-keep class com.lunacy.mdm.agent.KioskLauncherActivity { *; }

# --- kotlinx.serialization (annotation-based, package-agnostic) ---
# Covers @Serializable DTOs in :proto AND the private Payload classes in :core handlers, which a
# proto-only rule would miss → release-only serialization crashes.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit / OkHttp ---
-keep,allowobfuscation interface com.lunacy.mdm.core.net.MdmApi
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
