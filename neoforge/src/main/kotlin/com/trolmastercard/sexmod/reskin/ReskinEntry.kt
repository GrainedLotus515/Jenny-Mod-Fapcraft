package com.trolmastercard.sexmod.reskin

import java.io.File

/**
 * **Role.** MC-free metadata for one custom-model pack archive
 * (`.zip` or `.rar`) found anywhere under the custom-models tree.
 * Produced by [ReskinScanner] from the archive header/central directory
 * ONLY — no entry data is decompressed here, so indexing hundreds of packs is
 * fast and non-laggy.
 *
 * **Lazy contract.** Everything in this class is metadata. Byte content is
 * only read via [ReskinReader] when the user explicitly asks for a
 * preview or an Apply.
 *
 * This class has NO Minecraft imports by design (headless-testable); the MC
 * layer lives in [ReskinManager]/[ReskinBrowserScreen].
 */
open class ReskinEntry(@JvmField val source: File, @JvmField val kind: Kind) {
    enum class Kind { ZIP, RAR }

    /** One file inside the archive: path + uncompressed size from the header. */
    open class FileInfo(@JvmField val name: String, @JvmField val size: Long)

    @JvmField
    val displayName: String = source.name

    @JvmField
    val files: MutableList<FileInfo> = ArrayList()

    @JvmField
    val girls: MutableSet<String> = LinkedHashSet()

    @JvmField
    var hasGeo: Boolean = false

    @JvmField
    var hasTexture: Boolean = false

    @JvmField
    var hasAnimation: Boolean = false

    @JvmField
    var hasCfg: Boolean = false

    @JvmField
    var hasLegacyFlat: Boolean = false

    @JvmField
    var previewName: String? = null

    @JvmField
    var error: String? = null

    @JvmField
    var totalSize: Long = 0

    /** Whether this archive contains anything we can apply (reskin or legacy wear). */
    open fun isUsable(): Boolean = hasGeo || hasTexture || hasAnimation || hasCfg || hasLegacyFlat

    /** Whether this is a resourcepack-layout reskin (assets/sexmod/geo|textures|animations...). */
    open fun isReskin(): Boolean = hasGeo && (hasTexture || hasAnimation)

    open fun girlSummary(): String = if (girls.isEmpty()) "?" else girls.joinToString("/")

    open fun flagSummary(): String {
        val sb = StringBuilder()
        if (hasGeo) sb.append("geo ")
        if (hasTexture) sb.append("tex ")
        if (hasAnimation) sb.append("anim ")
        if (hasCfg) sb.append("cfg ")
        if (hasLegacyFlat) sb.append("legacy ")
        return sb.toString().trim()
    }

    /** Case-insensitive lookup of an entry by exact name; falls back to suffix match. */
    open fun resolveName(wanted: String): String? {
        val low = wanted.lowercase()
        for (fi in files) {
            val n = fi.name.lowercase()
            if (n == low) return fi.name
        }
        for (fi in files) {
            val n = fi.name.lowercase()
            if (n.endsWith("/$low") || n.endsWith(low)) return fi.name
        }
        return null
    }

    override fun toString(): String =
        displayName + " [" + flagSummary() + "] girls=" + girlSummary() + " files=" + files.size +
            " size=" + totalSize + (if (error == null) "" else " ERROR=$error")
}
