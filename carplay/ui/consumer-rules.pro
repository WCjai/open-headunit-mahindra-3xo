# DiPlay ships with R8 optimization off and reaches framework and its own classes by reflection
# (hidden APIs, app_process entry points, Bluetooth/Wi-Fi internals). Keep the CarPlay code and its
# crypto/mDNS libraries whole so a shrunk Open Headunit release behaves like DiPlay's own APK.
-keep class com.shilapi.xcertplay.** { *; }
-keep class org.concentus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class javax.jmdns.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**
