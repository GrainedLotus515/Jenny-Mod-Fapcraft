package com.trolmastercard.sexmod.entity

import java.util.regex.Pattern

/**
 * String codecs for the girl part-id lists and custom-model part sets.
 *
 * Extracted from [BaseGirlEntity]; every method here is pure (no entity
 * state). The entity keeps its public static methods as delegates so callers
 * in subclasses and renderers are unaffected.
 */
object GirlPartCodec {
    /** Joins part ids as `"3-4-5-"` (trailing separator, as before). */
    @JvmStatic
    fun encodePartIdList(parts: List<Int>): String {
        val sb = StringBuilder()

        for (part in parts) {
            sb.append(part)
            sb.append("-")
        }

        return sb.toString()
    }

    /** Java `String.split("-")` semantics: trailing empty tokens are dropped. */
    @JvmStatic
    fun decodePartIdList(code: String): List<Int> {
        val ids = ArrayList<Int>()
        val tokens = javaSplit(code, "-")

        for (token in tokens) {
            ids.add(Integer.parseInt(token))
        }

        return ids
    }

    /**
     * Splits a custom-model code on `'#'`, dropping empty tokens and the
     * legacy `"cross"` marker.
     */
    @JvmStatic
    fun parseCustomParts(code: String): HashSet<String> {
        val parts = javaSplit(code, "#")
        val set = HashSet<String>()

        for (part in parts) {
            if ("" != part && "cross" != part) {
                set.add(part)
            }
        }

        return set
    }

    /** @return `count` empty part names */
    @JvmStatic
    fun partNames(count: Int): List<String> {
        val names = ArrayList<String>()

        for (i in 0 until count) {
            names.add("")
        }

        return names
    }

    /** Joins a part set as `"name1#name2#"`; null/empty become "". */
    @JvmStatic
    fun encodeCustomParts(parts: HashSet<String>?): String {
        if (parts == null) {
            return ""
        }

        if (parts.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()

        for (part in parts) {
            builder.append(part)
            builder.append("#")
        }

        return builder.toString()
    }

    /** `java.lang.String.split(literal)`: like Java, drops trailing empty strings. */
    private fun javaSplit(s: String, sep: String): Array<String> {
        return Pattern.compile(Pattern.quote(sep)).split(s)
    }
}
