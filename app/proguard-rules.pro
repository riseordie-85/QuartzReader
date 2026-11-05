# Called from native code, so names must survive shrinking.
-keep class me.zhanghai.android.libarchive.** { *; }
-keep class com.gemalto.jp2.** { *; }

# Readium loads parts of itself by name and from WebView JavaScript.
-keep class org.readium.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Phase 0 only: a missing optional class shouldn't stop a test build.
-ignorewarnings
