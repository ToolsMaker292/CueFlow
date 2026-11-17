# Keep kotlinx.serialization generated serializers for release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cueflow.app.data.**$$serializer { *; }
-keepclassmembers class com.cueflow.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.cueflow.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose keeps required runtime metadata automatically.
-dontwarn org.jetbrains.annotations.**

# PDFBox's optional JPEG2000 decoder is not needed for text extraction.
-dontwarn com.gemalto.jp2.**

# PDFBox optional BouncyCastle signing/encryption support is excluded.
-dontwarn org.bouncycastle.**
