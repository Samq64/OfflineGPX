# VTM annotates with javax.annotation (JSR-305) without depending on it; the annotations
# are compile-time only.
-dontwarn javax.annotation.**
# vtm-android's SVG decoder, excluded in build.gradle.kts.
-dontwarn com.caverock.**
