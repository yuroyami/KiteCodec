package io.github.yuroyami.kitecodec

internal actual object JniLibrary {
    actual val isAndroid: Boolean = false
    @Volatile private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val override = System.getProperty("kitecodec.jni.path")
            if (override.isNullOrBlank()) System.loadLibrary("kitecodec_jni") else System.load(override)
            loaded = true
        }
    }
}
