# VibeView R8/ProGuard rules.
#
# The AirPlay network stack pulls in libraries that use reflection, JNI, and
# ServiceLoader lookups R8 cannot see through. Keeping them whole (and silencing
# warnings about their optional, unbundled dependencies) is the safe choice for a
# receiver where a stripped class means a runtime crash, not a smaller download.

# ---- Netty ----
-keep class io.netty.** { *; }
-keepclassmembers class io.netty.** { *; }
-dontwarn io.netty.**
# Netty references many optional codecs/transports we don't bundle.
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.google.protobuf.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.jboss.marshalling.**
-dontwarn reactor.blockhound.**
-dontwarn sun.security.**
-dontwarn java.lang.instrument.**

# ---- JmDNS (Bonjour advertising) ----
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# ---- dd-plist (binary/xml plist parsing) ----
-keep class com.dd.plist.** { *; }
-dontwarn com.dd.plist.**

# ---- EdDSA / Curve25519 (pairing crypto) ----
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
# curve25519-java selects its provider (native then pure-Java) via reflection.
-keep class org.whispersystems.curve25519.** { *; }
-dontwarn org.whispersystems.curve25519.**

# ---- Vendored jap2lib protocol core ----
# Loads FairPlay lookup tables as classpath resources; keep it intact.
-keep class com.github.serezhka.jap2lib.** { *; }

# ---- SLF4J (routed to Android logcat) ----
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ---- Kotlin coroutines ----
# The android artifact ships consumer rules, but keep the dispatcher factory
# and the ServiceLoader entry explicitly as a belt-and-braces measure.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-dontwarn kotlinx.coroutines.**

# Media3/AndroidX ship their own consumer rules; nothing extra needed here.
