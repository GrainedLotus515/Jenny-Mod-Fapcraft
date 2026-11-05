package com.trolmastercard.sexmod.reskin

import be.stef.rar.Unrar5j
import be.stef.rar4.Rar4Extractor
import be.stef.rar5.Rar5Extractor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * **Role.** MC-free lazy byte access into a [ReskinEntry].
 *
 * ZIP: entries are read straight out of the archive via [ZipFile] — no
 * temp files at all.
 *
 * RAR: unrar5j only exposes whole-archive extraction, so the first read of any
 * file in a `.rar` extracts the entire archive once into a cache dir
 * under `java.io.tmpdir` (keyed by canonical path hash). Subsequent
 * reads are plain file reads. The user's own folders are NEVER touched.
 *
 * Extraction is thread-safe and writes a `.extracted` marker so a second
 * run reuses the cache instead of re-extracting.
 */
object ReskinReader {
    private val RAR_CACHE: MutableMap<String, File> = ConcurrentHashMap()
    private const val MARKER = ".extracted"

    /** Read one entry's full bytes. Returns null if the entry doesn't exist. */
    @JvmStatic
    @Throws(IOException::class)
    fun read(e: ReskinEntry, relName: String): ByteArray? {
        val actual = e.resolveName(relName) ?: return null
        if (e.kind == ReskinEntry.Kind.ZIP) {
            ZipFile(e.source).use { zf ->
                val ze = findExact(zf, actual) ?: return null
                zf.getInputStream(ze).use { stream -> return readAll(stream) }
            }
        }
        val base = extractRar(e.source)
        val f = safeChild(base, actual)
        return if (f != null && f.isFile) Files.readAllBytes(f.toPath()) else null
    }

    /** The cache dir for a RAR archive (extracting on first use). */
    @JvmStatic
    @Throws(IOException::class)
    fun extractRar(src: File): File {
        val key = canonical(src)
        var cached = RAR_CACHE[key]
        if (cached != null && cached.isDirectory) return cached

        synchronized(RAR_CACHE) {
            cached = RAR_CACHE[key]
            val again = cached
            if (again != null && again.isDirectory) return again

            val dir = File(System.getProperty("java.io.tmpdir"), "sexmod_reskin_" + sha1(key))
            if (!File(dir, MARKER).isFile) {
                deleteTree(dir) // stale partial
                dir.mkdirs()
                // silence unrar5j's console progress bar
                Rar5Extractor.showProgress = false
                Rar4Extractor.showProgress = false
                try {
                    Unrar5j.extract(src.absolutePath, dir.absolutePath, "")
                } catch (t: Throwable) {
                    // extraction failed (e.g. encrypted) — still mark so we don't retry forever
                }
                val marker = File(dir, MARKER)
                try {
                    Files.write(marker.toPath(), ByteArray(0))
                } catch (ignored: IOException) {
                    // ignored: the marker only avoids re-extracting; the files are already unpacked
                }
            }
            RAR_CACHE[key] = dir
            return dir
        }
    }

    /** Directory listing of an extracted RAR (null-safe). */
    @JvmStatic
    @Throws(IOException::class)
    fun extractedFiles(src: File): List<File> {
        val dir = extractRar(src)
        val out = ArrayList<File>()
        collect(dir, out)
        return out
    }

    private fun collect(dir: File, out: MutableList<File>) {
        val kids = dir.listFiles() ?: return
        for (kid in kids) {
            if (kid.isDirectory) collect(kid, out) else out.add(kid)
        }
    }

    private fun safeChild(base: File, rel: String): File? {
        val f = File(base, rel)
        val normBase: File
        val normF: File
        try {
            normBase = base.canonicalFile
            normF = f.canonicalFile
        } catch (e: IOException) {
            return null
        }
        return if (normF.path.startsWith(normBase.path + File.separator)) normF else null
    }

    /**
     * Decode an image, tolerating decoy/junk files:
     * - salvages truncated PNGs (some decoys append garbage after the
     *   `IEND` chunk, which makes strict ImageIO fail);
     * - treats any file that decodes to a tiny (<=4px) image but is >4KB
     *   as a decoy and returns null.
     *
     * Returns null for undecodable/decoy images.
     */
    @JvmStatic
    fun decodeImage(bytes: ByteArray?): BufferedImage? {
        if (bytes == null || bytes.isEmpty()) return null
        var img = tryDecode(bytes)
        if (img == null) {
            // PNG decoys: tiny valid PNG followed by garbage; truncate at IEND
            val iend = indexOfAscii(bytes, "IEND")
            if (iend >= 8) {
                val truncated = bytes.copyOf(Math.min(bytes.size, iend + 8))
                img = tryDecode(truncated)
            }
        }
        if (img == null) return null
        if (bytes.size > 4096 && img.width <= 4 && img.height <= 4) {
            return null // 1x1 placeholder decoy
        }
        return img
    }

    private fun tryDecode(b: ByteArray): BufferedImage? {
        return try {
            ImageIO.read(ByteArrayInputStream(b))
        } catch (t: Throwable) {
            null
        }
    }

    private fun indexOfAscii(haystack: ByteArray, needle: String): Int {
        val nb = needle.toByteArray(Charsets.US_ASCII)
        var i = 0
        outer@ while (i <= haystack.size - nb.size) {
            for (j in nb.indices) {
                if (haystack[i + j] != nb[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private fun findExact(zf: ZipFile, name: String): ZipEntry? {
        val en = zf.entries()
        while (en.hasMoreElements()) {
            val ze = en.nextElement()
            if (ze.name == name) return ze
        }
        return null
    }

    @Throws(IOException::class)
    private fun readAll(stream: InputStream): ByteArray {
        val bos = ByteArrayOutputStream(8192)
        val buf = ByteArray(65536)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (e: IOException) {
        f.absolutePath
    }

    private fun sha1(s: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val d = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in d) sb.append(String.format("%02x", b.toInt() and 0xff))
            sb.toString()
        } catch (e: Exception) {
            Integer.toHexString(s.hashCode())
        }
    }

    private fun deleteTree(f: File?) {
        if (f == null || !f.exists()) return
        val kids = f.listFiles()
        if (kids != null) for (k in kids) deleteTree(k)
        f.delete()
    }
}
