# Keep kotlinx.serialization generated serializers for protocol classes.
-keepclassmembers class com.lunacy.mdm.proto.** {
    *** Companion;
}
-keepclasseswithmembers class com.lunacy.mdm.proto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
