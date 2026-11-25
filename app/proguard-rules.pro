# Thor Reader release shrinking (R8).

# MuPDF: its native code calls these classes, fields and methods by name.
-keep class com.artifex.mupdf.fitz.** { *; }
# Our classes that MuPDF's native code calls back into (streams, text walkers, devices).
-keep class * implements com.artifex.mupdf.fitz.SeekableInputStream { *; }
-keep class * implements com.artifex.mupdf.fitz.SeekableStream { *; }
-keep class * implements com.artifex.mupdf.fitz.StructuredTextWalker { *; }

# Enum names are stored in the database and preferences ("EPUB", "BOOK_REVERSED", ...).
-keep enum dev.thorreader.app.** { *; }

# Release builds log only warnings and errors (no file names, keys or progress chatter).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Optional extras of the archive libraries that Thor Reader doesn't use.
-dontwarn org.slf4j.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
