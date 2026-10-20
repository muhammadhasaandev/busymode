# JNI entry point used by the Node.js runtime glue
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.hasaan.busymode.NodeEngine { *; }

# Vosk (offline wake word) talks to native code through JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
