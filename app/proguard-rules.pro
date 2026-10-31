# C++ exports JNI symbols by declaring class and method name. Root the bindings,
# not their entire packages; non-native members remain shrinkable.
-keep,allowoptimization class org.screencopy.receiver.media.SrtNative {
    native <methods>;
}
-keep,allowoptimization class org.screencopy.receiver.media.OpusDecoder {
    native <methods>;
}

# AGP generates manifest-component rules for MainActivity and UpdateFileProvider.
# Media3 reflection uses its upstream consumer rules. Updater JSON is parsed
# explicitly, so no model-field or whole updater-package keep is needed.
