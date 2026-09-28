# VTM uses compile-time-only JSR-305 annotations without depending on them.
-dontwarn javax.annotation.**
# vtm-android's SVG decoder, excluded in build.gradle.kts.
-dontwarn com.caverock.**
