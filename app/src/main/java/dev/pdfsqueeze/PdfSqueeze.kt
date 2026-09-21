package dev.pdfsqueeze

/**
 * Raw JNI facade over `libpdfsqueeze.so` (built from `crates/ffi`).
 * Prefer [PdfSqueezeClient] from app code: it adds coroutines, progress and cancellation.
 */
object PdfSqueeze {
    init {
        System.loadLibrary("pdfsqueeze")
    }

    /**
     * Compress a PDF. Blocks the calling thread (call from a background thread).
     * @param optionsJson e.g. `{"profile":"small"}`, or a full options object from [optionsJson].
     * @throws IllegalArgumentException bad options
     * @throws java.util.concurrent.CancellationException after [cancel]
     * @throws RuntimeException parse failure, encrypted input, internal error
     */
    @JvmStatic external fun compress(input: ByteArray, optionsJson: String): ByteArray

    /** JSON `Analysis`: byte budget by category, images with effective DPI, notes. */
    @JvmStatic external fun analyze(input: ByteArray): String

    /** JSON `Report` of the last successful [compress] on this process. */
    @JvmStatic external fun lastReport(): String

    /** Progress of the running [compress], 0..100 (poll from another thread). */
    @JvmStatic external fun progress(): Int

    /** Ask the running [compress] to stop; it throws CancellationException shortly after. */
    @JvmStatic external fun cancel(): Boolean

    /** Full options JSON for a preset: "lossless" | "balanced" | "small" | "extreme". */
    @JvmStatic external fun optionsJson(profile: String): String

    @JvmStatic external fun version(): String
}
