# R8 may shrink/obfuscate the app code normally. No reflective access to
# com.devbox classes exists: the only reflection targets java.lang.Process
# (kept by the platform rules). The WebView JS bridge is no longer registered;
# the rule is kept in case WebViewInterface is ever re-exposed.
-keepclassmembers class com.devbox.WebViewInterface {
    public *;
}
