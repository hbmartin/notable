# Keep line numbers for readable release stack traces (file names hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Onyx SDK -----------------------------------------------------------
# The Onyx e-ink SDK resolves device/driver classes reflectively and talks
# to system services by class name; shrinking or renaming it breaks pen
# input on Boox devices.
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**

# --- MuPDF (fitz) -------------------------------------------------------
# JNI: native code instantiates Java classes and calls methods by name.
-keep class com.artifex.mupdf.fitz.** { *; }

# Keep native method names in general so JNI registration keeps working.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- ShipBook logging SDK -----------------------------------------------
-keep class io.shipbook.shipbooksdk.** { *; }
-dontwarn io.shipbook.shipbooksdk.**

# ShipBook depends on the SLF4J API without a bundled backend.
-dontwarn org.slf4j.**

# --- HiddenApiBypass (deep reflection into java.lang) ---------------------
-keep class org.lsposed.hiddenapibypass.** { *; }

# --- kotlinx.serialization ------------------------------------------------
# @Serializable app models (Room type converters, sync payloads) are looked
# up through generated companion serializers.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.ethran.notable.** {
    *** Companion;
}
-keepclasseswithmembers class com.ethran.notable.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ethran.notable.**$$serializer { *; }

# --- Optional/compile-only references pulled in by libraries --------------
-dontwarn org.joda.convert.FromString
-dontwarn org.joda.convert.ToString
-dontwarn java.lang.management.**
