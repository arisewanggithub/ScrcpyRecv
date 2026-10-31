# These rules apply only to the instrumentation APK, never the app's R8 graph.
# AndroidJUnitRunner receives the exact test class names from the CI selector.
# Error Prone 2.41.0 IncompatibleModifiers is CLASS-retained annotation-type
# metadata for compiler analysis. Its deprecated value() mentions a JDK compiler
# enum absent on Android; no runtime/test path uses that enum.
-dontwarn javax.lang.model.element.Modifier

-keep class dev.scrcpy.smoke.ReleaseSmokeRunner {
    public <init>();
}
-keep class org.screencopy.receiver.media.NativeSrtTransportTest {
    public <init>();
    @org.junit.Test public void *(...);
}
-keep class org.screencopy.receiver.media.OpusDecoderTest {
    public <init>();
    @org.junit.Test public void *(...);
}
-keep class org.screencopy.receiver.ReleaseRuntimeSmokeTest {
    public <init>();
    @org.junit.Test public void *(...);
}
