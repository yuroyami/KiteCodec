-keep class io.github.yuroyami.kitecodec.Internals {
    native <methods>;
}

# kj_util.c resolves these binary names with FindClass and explicitly invokes the exact
# (Ljava/lang/String;)V constructor. Neither lookup is visible to R8's ordinary reachability
# analysis, so both the class names and constructors are part of the JNI ABI.
-keep class io.github.yuroyami.kitecodec.JniHandleException {
    <init>(java.lang.String);
}

-keep class io.github.yuroyami.kitecodec.JniNativeException {
    <init>(java.lang.String);
}
