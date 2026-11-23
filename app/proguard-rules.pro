# Keep JNI classes and native methods for R8/ProGuard release builds
-keep class hev.htproxy.TProxyService {
    public static native <methods>;
    private static native <methods>;
}

-keep class io.github.jackbrownua.netsieve.core.NativeBridge {
    native <methods>;
}
