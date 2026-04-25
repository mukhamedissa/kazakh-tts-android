# Keep sherpa-onnx JNI surface
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep the specialized Function1 invoke signature required by sherpa-onnx JNI.
# D8 may otherwise drop the specialized bridge that JNI looks up by name.
-keep class * implements kotlin.jvm.functions.Function1 {
    java.lang.Object invoke(java.lang.Object);
    java.lang.Integer invoke(float[]);
}
