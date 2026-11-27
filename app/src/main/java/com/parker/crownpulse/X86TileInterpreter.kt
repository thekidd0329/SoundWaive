package com.parker.crownpulse

import android.content.res.AssetManager

/** Loaded only in a 32-bit x86 process. All other ABIs use CompiledModel. */
internal object X86TileInterpreter {
    val available = try {
        System.loadLibrary("soundwaive_x86_sharpen")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun open(assets: AssetManager, path: String, threads: Int): Long
    external fun run(handle: Long, input: FloatArray, output: FloatArray): Boolean
    external fun close(handle: Long)
}
