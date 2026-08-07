# Add project specific ProGuard rules here.
# code-server and Node.js native libs
-keep class com.devbox.** { *; }
-dontwarn com.devbox.**

# Keep WebView JS interface
-keepclassmembers class com.devbox.WebViewInterface {
    public *;
}
