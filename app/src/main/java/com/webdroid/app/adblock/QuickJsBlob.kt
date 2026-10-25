package com.webdroid.app.adblock

import app.cash.zipline.QuickJs
import java.io.InputStream

/**
 * Moves a large binary blob into QuickJS as an ArrayBuffer without decoding
 * it in interpreted JavaScript.
 *
 * QuickJS bytecode can carry an ArrayBuffer constant, which its loader
 * restores with a single memcpy. We compile `__wdBlob(<marker double>)`,
 * locate the marker's FLOAT64 entry in the constant pool and swap it for an
 * ARRAY_BUFFER entry holding the blob. Executing the patched bytecode calls
 * `__wdBlob(arrayBuffer)` inside the engine.
 *
 * Must run on the thread that owns [QuickJs].
 */
object QuickJsBlob {
    private const val MARKER = 1.2345678901234567e-300
    private const val TAG_FLOAT64 = 6

    // BC_TAG_ARRAY_BUFFER is 17 when QuickJS is built with CONFIG_BIGNUM
    // (as Zipline is), 15 otherwise; probed once and cached.
    private val CANDIDATE_TAGS = intArrayOf(17, 15)
    @Volatile private var arrayBufferTag = -1

    private class Template(val bytecode: ByteArray, val markerAt: Int)

    private fun template(js: QuickJs): Template {
        val bytecode = js.compile("__wdBlob($MARKER)", "blob.js")
        val bits = java.lang.Double.doubleToRawLongBits(MARKER)
        val pattern = ByteArray(9)
        pattern[0] = TAG_FLOAT64.toByte()
        for (i in 0 until 8) pattern[1 + i] = (bits ushr (8 * i)).toByte()
        outer@ for (i in 0..bytecode.size - pattern.size) {
            for (j in pattern.indices) if (bytecode[i + j] != pattern[j]) continue@outer
            return Template(bytecode, i)
        }
        error("blob marker not found in bytecode")
    }

    private fun leb128(value: Int): ByteArray {
        val out = ArrayList<Byte>(5)
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0)
        return out.toByteArray()
    }

    /** Builds patched bytecode, streaming the blob straight into place. */
    private fun craft(template: Template, tag: Int, size: Int, fill: (ByteArray, Int) -> Unit): ByteArray {
        val bc = template.bytecode
        val at = template.markerAt
        val len = leb128(size)
        val out = ByteArray(at + 1 + len.size + size + (bc.size - at - 9))
        System.arraycopy(bc, 0, out, 0, at)
        out[at] = tag.toByte()
        System.arraycopy(len, 0, out, at + 1, len.size)
        fill(out, at + 1 + len.size)
        System.arraycopy(bc, at + 9, out, at + 1 + len.size + size, bc.size - at - 9)
        return out
    }

    private fun probeTag(js: QuickJs, template: Template): Int {
        for (tag in CANDIDATE_TAGS) {
            val ok = runCatching {
                val result = js.execute(craft(template, tag, 16) { _, _ -> })
                (result as? Number)?.toInt() == 16
            }.getOrDefault(false)
            if (ok) return tag
        }
        error("unable to determine QuickJS ArrayBuffer tag")
    }

    /** Delivers [size] bytes read from [input] to `__wdBlob` in [js]. */
    fun inject(js: QuickJs, input: InputStream, size: Int) {
        val template = template(js)
        if (arrayBufferTag == -1) arrayBufferTag = probeTag(js, template)
        val bytecode = craft(template, arrayBufferTag, size) { buffer, offset ->
            var read = 0
            while (read < size) {
                val n = input.read(buffer, offset + read, size - read)
                if (n < 0) error("blob truncated: $read of $size bytes")
                read += n
            }
        }
        val result = js.execute(bytecode)
        check((result as? Number)?.toInt() == size) { "blob injection failed: $result" }
    }
}
