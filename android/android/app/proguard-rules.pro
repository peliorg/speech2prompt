# Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Bluetooth Serial
-keep class com.github.nickspo.flutter_bluetooth_serial.** { *; }

# Speech to Text
-keep class com.csdcorp.speech_to_text.** { *; }

# Secure Storage
-keep class com.it_nomads.fluttersecurestorage.** { *; }

# Crypto libraries
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
