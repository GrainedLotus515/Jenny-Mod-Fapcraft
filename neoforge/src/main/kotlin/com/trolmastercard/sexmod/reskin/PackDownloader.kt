package com.trolmastercard.sexmod.reskin

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * **Role.** MC-free downloader + extractor for the custom-models archive.
 *
 * Downloads the GitHub archive zip (`main.zip`) and extracts every pack
 * into the instance's custom-models directory (e.g.
 * `sexmod/custom_models/singleplayer`), stripping the top-level folder
 * GitHub adds (`Jenny-Mod-Custom-Models-Archive-main/`) so packs land
 * directly alongside the user's own models.
 *
 * Safety: entries are resolved against the destination with a canonical-path
 * check (no `..` traversal), directories are created, and the whole
 * operation is wrapped so a failure returns a message instead of throwing.
 * Pure Java — no Minecraft imports (headless-testable).
 */
object PackDownloader {
    /** Source archive. */
    const val ARCHIVE_URL: String = "https://github.com/ReverseEngineeringEnthusiasts/Jenny-Mod-Custom-Models-Archive/archive/refs/heads/main.zip"

    /** GitHub page for "View on Github". */
    const val PAGE_URL: String = "https://github.com/ReverseEngineeringEnthusiasts/Jenny-Mod-Custom-Models-Archive"

    /** Downloads `url` into a temp file and returns it (caller cleans up). */
    @JvmStatic
    @Throws(IOException::class)
    fun downloadToTemp(url: String): File {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "FapcraftMod/1.1 (sexmod reskin downloader)")
        val code = conn.responseCode
        if (code != 200) {
            throw IOException("HTTP $code from $url")
        }
        val tmp = File.createTempFile("sexmod_packs", ".zip")
        tmp.deleteOnExit()
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > 1024L * 1024 * 1024) { // 1GB sanity cap (archive is ~560MB)
                        throw IOException("Archive too large (>1GB)")
                    }
                }
            }
        }
        return tmp
    }

    /**
     * Extracts the zip archive into `destDir`, stripping the single top-level
     * folder added by GitHub. Returns a summary string; never throws for corrupt
     * entries individually (they're reported in the summary).
     */
    @JvmStatic
    fun extractTo(zip: File?, destDir: File): String {
        if (zip == null || !zip.isFile) return "Downloaded file is missing."
        destDir.mkdirs()
        var count = 0
        var bytes = 0L
        val problems = StringBuilder()
        try {
            ZipInputStream(BufferedInputStream(Files.newInputStream(zip.toPath()))).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name: String? = entry.name
                    if (name == null || name.isEmpty() || name.startsWith("/")) continue
                    // strip the single top-level folder (e.g. "Jenny-Mod-Custom-Models-Archive-main/")
                    var rel: String = name
                    val slash = name.indexOf('/')
                    if (slash >= 0) {
                        rel = name.substring(slash + 1)
                    }
                    if (rel.isEmpty()) continue // the top folder itself

                    val target = File(destDir, rel)
                    // path traversal guard
                    val norm: File
                    val normDest: File
                    try {
                        norm = target.canonicalFile
                        normDest = destDir.canonicalFile
                    } catch (e: IOException) {
                        problems.append("skip ").append(name).append(" (bad path)\n")
                        continue
                    }
                    if (!norm.path.startsWith(normDest.path + File.separator) && norm != normDest) {
                        problems.append("skip ").append(name).append(" (unsafe path)\n")
                        continue
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        problems.append("skip ").append(name).append(" (cannot create dir)\n")
                        continue
                    }
                    try {
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = zin.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                bytes += n
                            }
                            count++
                        }
                    } catch (e: IOException) {
                        problems.append("skip ").append(name).append(" (").append(e.message).append(")\n")
                    }
                }
            }
        } catch (e: IOException) {
            return "Extraction failed: " + e.message
        }
        val sb = StringBuilder()
        sb.append("Installed ").append(count).append(" file(s)")
        if (bytes > 0) sb.append(" (").append(Math.round(bytes / 1024.0)).append(" KB)")
        sb.append(" into ").append(destDir.absolutePath).append(".")
        if (problems.isNotEmpty()) {
            sb.append(" Some files were skipped:\n").append(problems)
        }
        return sb.toString()
    }
}
