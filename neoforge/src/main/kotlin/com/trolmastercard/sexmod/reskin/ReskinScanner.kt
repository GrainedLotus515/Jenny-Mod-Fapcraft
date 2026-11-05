package com.trolmastercard.sexmod.reskin

import be.stef.rar.Unrar5j
import be.stef.rar4.Rar4HeaderParser
import be.stef.rar5.Rar5Reader
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/**
 * **Role.** MC-free recursive scanner for custom-model packs.
 *
 * Walks the custom-models tree at ANY nesting depth and indexes every
 * `.zip` (via the central directory — no decompression) and `.rar`
 * (via the unrar5j header parser — no decompression). For each archive it
 * records geo/texture/animation/cfg presence, the target girl(s), and the best
 * preview PNG — everything needed for a responsive GUI with zero lag at
 * startup.
 *
 * **Read-only.** This never moves, renames or extracts anything into the
 * user's folder; the user's directory layout is left exactly as-is.
 *
 * Port note: needs the `io.github.realburst:unrar5j:v2.0.4` library (bundled
 * in the 1.12 jar; must be added to the NeoForge build as `jarJar`).
 */
open class ReskinScanner {
    open fun scan(root: File?): List<ReskinEntry> {
        val out = ArrayList<ReskinEntry>()
        if (root != null && root.isDirectory) {
            walk(root, out)
        }
        return out
    }

    private fun walk(dir: File, out: MutableList<ReskinEntry>) {
        val kids = dir.listFiles() ?: return
        for (kid in kids) {
            if (kid.isDirectory) {
                walk(kid, out)
            } else {
                val lower = kid.name.lowercase(Locale.ROOT)
                if (lower.endsWith(".zip") || lower.endsWith(".rar")) {
                    val e = index(kid)
                    if (e != null) out.add(e)
                }
            }
        }
    }

    companion object {
        /** Known girl names (the canonical `assets/sexmod/geo/<girl>/...` folder names). */
        @JvmField
        val GIRLS: List<String> = listOf(
            "jenny", "ellie", "allie", "bee", "bia", "galath", "goblin", "kobold", "luna", "slime", "cat", "manglelie"
        )

        /** Index one archive file into a [ReskinEntry] (metadata only). */
        @JvmStatic
        fun index(file: File): ReskinEntry? {
            val lower = file.name.lowercase(Locale.ROOT)
            if (lower.endsWith(".zip")) return indexZip(file)
            if (lower.endsWith(".rar")) return indexRar(file)
            return null
        }

        private fun indexZip(file: File): ReskinEntry {
            val e = ReskinEntry(file, ReskinEntry.Kind.ZIP)
            try {
                ZipFile(file).use { zf ->
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val ze = en.nextElement()
                        if (ze.isDirectory) continue
                        val name = ze.name
                        val size = ze.size
                        e.files.add(ReskinEntry.FileInfo(name, size))
                        e.totalSize += Math.max(0L, size)
                        classify(e, name)
                    }
                }
            } catch (ex: IOException) {
                e.error = ex.message
            } catch (t: Throwable) {
                e.error = t.javaClass.simpleName + ": " + t.message
            }
            finish(e)
            return e
        }

        private fun indexRar(file: File): ReskinEntry {
            val e = ReskinEntry(file, ReskinEntry.Kind.RAR)
            try {
                val fmt = Unrar5j.detectFormat(file.absolutePath)
                if (fmt == Unrar5j.FORMAT_RAR5) {
                    val reader = Rar5Reader()
                    if (!reader.read(file)) {
                        e.error = "RAR5 header parse failed (corrupted / encrypted headers?)"
                        return finish(e)
                    }
                    val blocks = reader.getFileBlocks()
                    if (blocks == null || blocks.isEmpty()) {
                        e.error = "no file blocks (encrypted headers?)"
                        return finish(e)
                    }
                    for (b in blocks) {
                        if (b == null || b.isDirectory()) continue
                        val name: String? = b.getFileName()
                        if (name == null || name.isEmpty()) continue
                        val size = safeSize(b.getUnpackedSize())
                        e.files.add(ReskinEntry.FileInfo(name, size))
                        e.totalSize += Math.max(0L, size)
                        classify(e, name)
                    }
                } else if (fmt == Unrar5j.FORMAT_RAR4) {
                    val parser = Rar4HeaderParser()
                    if (!parser.parse(file)) {
                        e.error = "RAR4 header parse failed (corrupted / encrypted headers?)"
                        return finish(e)
                    }
                    val blocks = parser.getFileBlocks()
                    if (blocks == null || blocks.isEmpty()) {
                        e.error = "no file blocks (encrypted headers?)"
                        return finish(e)
                    }
                    for (b in blocks) {
                        if (b == null || b.isDirectory()) continue
                        val name: String? = b.getFileName()
                        if (name == null || name.isEmpty()) continue
                        val size = safeSize(b.getUnpackedSize())
                        e.files.add(ReskinEntry.FileInfo(name, size))
                        e.totalSize += Math.max(0L, size)
                        classify(e, name)
                    }
                } else {
                    e.error = "unknown RAR format"
                }
            } catch (t: Throwable) {
                e.error = t.javaClass.simpleName + ": " + t.message
            }
            return finish(e)
        }

        private fun safeSize(v: Long): Long = if (v < 0) 0L else v

        /** Classify one archive entry name into geo/texture/animation/cfg + girl + preview. */
        @JvmStatic
        fun classify(e: ReskinEntry, name: String) {
            val n = name.replace('\\', '/')
            val low = n.lowercase(Locale.ROOT)
            val girl = detectGirl(low)

            if (low.endsWith(".cfg")) {
                e.hasCfg = true
            }
            if (low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg")) {
                if (low.endsWith("pack.png") || low.endsWith("pack.jpg")) {
                    // pack.png wins over anything else as the preview thumbnail
                    e.previewName = name
                } else if (e.previewName == null) {
                    e.previewName = name // first PNG seen = fallback preview
                }
            }
            if (low.endsWith(".geo.json") || (low.endsWith(".json") && low.contains("/geo/"))) {
                e.hasGeo = true
                if (girl != null) e.girls.add(girl)
            }
            if (low.contains("/textures/") && (low.endsWith(".png") || low.endsWith(".jpg"))) {
                e.hasTexture = true
                if (girl != null) e.girls.add(girl)
            }
            if (low.contains("/animations/") && low.endsWith(".json")) {
                e.hasAnimation = true
                if (girl != null) e.girls.add(girl)
            }
            // old flat layout: assets/<girl>.geo.json (no assets/sexmod/...)
            if (low.matches(Regex("assets/[a-z0-9]+[^/]*\\.geo\\.json")) && !low.contains("/sexmod/")) {
                e.hasLegacyFlat = true
                e.hasGeo = true
                val slash = low.indexOf('/')
                val dot = low.indexOf('.', slash)
                if (slash >= 0 && dot > slash) e.girls.add(low.substring(slash + 1, dot))
            }
        }

        /** Find a known girl token in the path (folder segment equals a girl name). */
        @JvmStatic
        fun detectGirl(lowPath: String): String? {
            val segs = lowPath.split("/")
            for (seg in segs) {
                val s = seg.lowercase(Locale.ROOT)
                if (GIRLS.contains(s)) return s
            }
            return null
        }

        private fun finish(e: ReskinEntry): ReskinEntry {
            if (e.previewName == null && e.files.isNotEmpty()) {
                // last resort: any png in the archive
                for (fi in e.files) {
                    val low = fi.name.lowercase(Locale.ROOT)
                    if (low.endsWith(".png")) {
                        e.previewName = fi.name
                        break
                    }
                }
            }
            return e
        }
    }
}
