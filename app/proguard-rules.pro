# The vault model is serialized by kotlinx.serialization; keep its generated
# serializers so a release build still reads vaults written by the desktop app.
-keepclassmembers class com.qtotp.mobile.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.qtotp.mobile.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit loads barcode model classes reflectively.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
