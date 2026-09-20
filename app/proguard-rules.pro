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

# iText PDF — narrowed keep rules (#325, ex-#145). Sole consumer is
# PdfGeneratorService (images → PDF via PdfWriter/PdfDocument/Document/Image/
# PageSize/ImageDataFactory). Statically reached code survives R8 shrinking on
# its own — keeps are only required where iText resolves types REFLECTIVELY.
# We therefore keep the reflective subsystems (kernel.pdf object model, layout
# renderers, kernel events) instead of blanket-keeping every com.itextpdf class;
# unused subsystems (font tables, barcodes, forms, signatures, svg, pdf/a) are
# now shrinkable and obfuscatable.
-keep class com.itextpdf.io.image.ImageDataFactory { *; }
-keep class com.itextpdf.kernel.geom.PageSize { *; }
-keep class com.itextpdf.kernel.pdf.** { *; }
-keep class com.itextpdf.layout.** { *; }

# iText bootstraps its DI container via Class.forName (DIContainerConfigurations →
# RegisterDefaultDiContainer) and resolves page-tree factories through it — the
# reflected entry point and the DI plumbing must survive shrinking or PdfDocument
# construction fails at runtime in minified builds (codex P1; paths verified
# against the iText 9.0.0 jars).
-keep class com.itextpdf.kernel.utils.RegisterDefaultDiContainer { *; }
-keep class com.itextpdf.kernel.di.** { *; }
-keep class com.itextpdf.commons.utils.DIContainer { *; }
-keep class com.itextpdf.commons.utils.DIContainerConfigurations { *; }

# iText validates event class NAMES at runtime: AbstractITextEvent rejects any
# event whose getClass().getName() does not start with "com.itextpdf." (e.g.
# FlushPdfDocumentEvent on PdfDocument.close()). R8 may therefore SHRINK unused
# iText code, but must never RENAME what remains (codex P1).
-keepnames class com.itextpdf.** { *; }

# Keep event handler classes (fixes AbstractTextEvent errors)
-keep class com.itextpdf.kernel.events.** { *; }

# Keep classes used via reflection
-keepclassmembers class * extends com.itextpdf.kernel.pdf.PdfObject {
    <init>(...);
}

# Preserve line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable

# Keep exception class NAMES readable.
#
# PaperlessException.diagnosticTag is derived from the throwable's simple name and is
# shown to the user ("Unknown error (SocketException)"), sent as an analytics dimension,
# and carried in the diagnostic report a user can mail us. All three exist so that an
# unclassifiable failure can still be told apart from another one — that is the entire
# point of the property.
#
# Without this rule the value is obfuscated in exactly the build users run: a screenshot
# would read "Unknown error (a)", and the report would be no more useful than the bare
# "Unknown error" it replaced. That failure already cost this project one round trip —
# a Play review in July was answered with a fix whose user-visible effect was nil, and
# the same complaint came back in September.
#
# -keepnames, not -keep: R8 may still SHRINK unreachable exception classes, it may only
# not RENAME what survives. Same distinction the iText rule above relies on.
-keepnames class * extends java.lang.Throwable

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
