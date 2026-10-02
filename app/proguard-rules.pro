# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.echobharat.schema.** { *; }
-keep class com.echobharat.mesh.** { *; }

# ONNX Runtime's JNI layer constructs and calls these classes by name (OnnxTensor,
# OrtException, OrtSession.Result, ...). The AAR ships no consumer rules, so without this
# R8 would rename them and the native lookups would fail at the first inference.
-keep class ai.onnxruntime.** { *; }

# Conversation history is written with Gson by field name. Obfuscated names would change
# between releases and make every saved history unreadable after an update. Gson also needs
# the generic signatures to read List<Conversation> back, which R8 full mode strips.
-keepattributes Signature
-keep class com.echobharat.conversation.** { <fields>; <init>(...); }
