package com.webdroid.app.adblock

/** Encodes a Kotlin string as a JavaScript string literal. */
object JsLiteral {
    private val HEX = "0123456789abcdef".toCharArray()

    fun of(value: String): String {
        val sb = StringBuilder(value.length + 16)
        append(sb, value)
        return sb.toString()
    }

    fun append(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') {
                    sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /** `fn("a","b",...)` */
    fun call(function: String, vararg args: String): String {
        val sb = StringBuilder(function.length + args.sumOf { it.length + 8 } + 4)
        sb.append(function).append('(')
        args.forEachIndexed { i, arg ->
            if (i != 0) sb.append(',')
            append(sb, arg)
        }
        sb.append(')')
        return sb.toString()
    }
}
