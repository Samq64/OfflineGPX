# Room and kotlinx.serialization both ship consumer rules, as do Compose and AndroidX,
# so the generated DAOs and the @Serializable routes survive R8 on their own.
#
# The exception is navigation's type-safe routes under R8 full mode: a route is only ever
# referenced reflectively through its serializer, so keep the classes and the companion
# objects that hold them.
-keep,allowobfuscation,allowshrinking class dev.samuelq.gpx.ui.nav.** { *; }
-keepclassmembers class dev.samuelq.gpx.ui.nav.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
