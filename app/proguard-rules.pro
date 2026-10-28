# Ignore warnings about optional classes referenced by libraries
-ignorewarnings

# yt-dlp / ffmpeg wrapper
-keep class com.yausername.** { *; }

# Our own code (small)
-keep class com.admoss.downloader.** { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
