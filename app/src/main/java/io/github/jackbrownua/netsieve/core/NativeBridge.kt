package io.github.jackbrownua.netsieve.core

object NativeBridge {

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("netsieve")
            loaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun abi(): String = if (ensureLoaded()) nativeAbi() else "native library not loaded"

    fun runByeDpi(args: Array<String>): Int =
        if (ensureLoaded()) nativeRunByeDpi(args) else -1

    @JvmStatic
    private external fun nativeAbi(): String

    @JvmStatic
    private external fun nativeRunByeDpi(args: Array<String>): Int
}
