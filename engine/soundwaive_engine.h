/* Public C API for the SoundWaive analysis engine.
 *
 * This is the contract the iOS build (Kotlin/Native cinterop) and any other
 * non-Android embedding use. It mirrors the Android JNI `analyze` entry point:
 * the input is a raw little-endian i16 PCM file at 11025 Hz mono, the output is
 * a `CPULSE01` analysis file, and the CPULSE01 format is byte-identical on
 * every platform. See engine/src/lib.rs for the format and engine/src/capi.rs
 * for the implementation.
 */
#ifndef SOUNDWAIVE_ENGINE_H
#define SOUNDWAIVE_ENGINE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Analyze the raw PCM file at `input` and write the `CPULSE01` analysis to
 * `output`. Returns NULL on success. On failure returns a heap-allocated
 * UTF-8 error message that the caller must release with
 * soundwaive_free_string. */
char *soundwaive_analyze(const char *input, const char *output);

/* Release a string returned by soundwaive_analyze. Passing NULL is a no-op. */
void soundwaive_free_string(char *s);

/* Engine version, e.g. "1.0.0". The returned pointer is a static string;
 * do not free it. */
const char *soundwaive_version(void);

#ifdef __cplusplus
}
#endif

#endif /* SOUNDWAIVE_ENGINE_H */
