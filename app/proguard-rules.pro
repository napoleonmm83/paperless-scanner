# Paperless Scanner ProGuard Rules

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

-keep class com.paperless.scanner.data.api.models.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson TypeToken - required for R8 full mode
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# iText PDF - ignore optional dependencies not used in Android
# BouncyCastle (encryption/signing - not used)
-dontwarn com.itextpdf.bouncycastle.**
-dontwarn com.itextpdf.bouncycastlefips.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**

# Jackson JSON (optional JSON utilities - not used)
-dontwarn com.fasterxml.jackson.**

# Java AWT/ImageIO (desktop image processing - not used, we use Android Bitmap)
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# iText PDF - Keep rules for core functionality
# Keep all iText classes to prevent obfuscation issues
-keep class com.itextpdf.** { *; }

# Keep all iText interfaces and abstract classes
-keep interface com.itextpdf.** { *; }
-keep abstract class com.itextpdf.** { *; }

# Keep event handler classes (fixes AbstractTextEvent errors)
-keep class com.itextpdf.kernel.events.** { *; }
-keep class com.itextpdf.layout.** { *; }

# Keep classes used via reflection
-keepclassmembers class * extends com.itextpdf.kernel.pdf.PdfObject {
    <init>(...);
}

# Preserve line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable

# Google Play Billing Library
# Prevent obfuscation of billing classes that may cause ProxyBillingActivity crashes
-keep class com.android.billingclient.** { *; }
-keep interface com.android.billingclient.** { *; }

# Keep billing-related classes that use reflection
-keepclassmembers class * implements com.android.billingclient.api.PurchasesUpdatedListener {
    *;
}
-keepclassmembers class * implements com.android.billingclient.api.BillingClientStateListener {
    *;
}

# Prevent R8 from removing billing activity
-keep class com.android.billingclient.api.ProxyBillingActivity { *; }
