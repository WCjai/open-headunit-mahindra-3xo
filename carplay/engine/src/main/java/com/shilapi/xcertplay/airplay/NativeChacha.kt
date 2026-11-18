package com.shilapi.xcertplay.airplay

/**
 * Open Headunit addition: ChaCha20-Poly1305 in C (jni/chacha_poly.c), for Android releases whose
 * platform crypto has none (Android 8 and older). BouncyCastle's Java version decrypts CarPlay
 * video at 1-4 MB/s on a Cortex-A7 head unit, 10-90 ms a frame; this one about 28 MB/s, ~1 ms.
 * [available] is false when the library cannot load (unit tests on a desktop JVM).
 */
internal object NativeChacha {

    val available: Boolean = runCatching { System.loadLibrary("xcertplay_chacha") }.isSuccess

    /** The ciphertext followed by the 16-byte tag; null on malformed arguments. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray? =
        nativeSeal(key, nonce, plaintext, aad)

    /** The plaintext; null when the tag does not match or the arguments are malformed. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray? =
        nativeOpen(key, nonce, ciphertextAndTag, aad)

    @JvmStatic private external fun nativeSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray?
    @JvmStatic private external fun nativeOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?
}
