//! C API for non-Android embeddings (iOS via Kotlin/Native cinterop).
//!
//! Mirrors the Android JNI `analyze` entry point: same input (raw little-endian
//! i16 PCM at 11025 Hz mono), same output (a `CPULSE01` analysis file) and the
//! same error reporting (an error message, or nothing on success). The CPULSE01
//! format is unchanged; see `analyze_pcm`.

use std::ffi::{c_char, CStr, CString};
use std::path::Path;

/// Read a NUL-terminated UTF-8 C string. Null pointers and invalid UTF-8 are errors.
fn read_c_string(ptr: *const c_char) -> Result<String, String> {
    if ptr.is_null() {
        return Err("null path".to_string());
    }
    // SAFETY: the caller guarantees `ptr` points at a valid NUL-terminated string.
    let s = unsafe { CStr::from_ptr(ptr) };
    s.to_str().map(str::to_string).map_err(|e| e.to_string())
}

/// Analyze the raw PCM file at `input` and write the `CPULSE01` analysis to `output`.
///
/// Returns null on success. On failure returns a heap-allocated UTF-8 error message
/// that the caller must release with [`soundwaive_free_string`].
#[no_mangle]
pub extern "C" fn soundwaive_analyze(input: *const c_char, output: *const c_char) -> *mut c_char {
    let result = (|| -> Result<(), String> {
        let i = read_c_string(input)?;
        let o = read_c_string(output)?;
        crate::analyze_pcm(Path::new(&i), Path::new(&o)).map_err(|e| e.to_string())?;
        Ok(())
    })();
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => CString::new(e).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

/// Release a string returned by [`soundwaive_analyze`]. Passing null is a no-op.
#[no_mangle]
pub extern "C" fn soundwaive_free_string(s: *mut c_char) {
    if !s.is_null() {
        // SAFETY: `s` came from `soundwaive_analyze` (CString::into_raw).
        unsafe {
            let _ = CString::from_raw(s);
        }
    }
}

/// Engine version, e.g. "1.0.0". The returned pointer is a static string; do not free it.
#[no_mangle]
pub extern "C" fn soundwaive_version() -> *const c_char {
    b"1.0.0\0".as_ptr() as *const c_char
}
