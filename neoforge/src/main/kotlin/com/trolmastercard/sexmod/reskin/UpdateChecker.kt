package com.trolmastercard.sexmod.reskin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.trolmastercard.sexmod.BuildInfo
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.regex.Pattern

/**
 * **Role.** MC-free update checker for the mod.
 *
 * On the FIRST world join of a session it queries the GitHub releases API and
 * compares the newest release tag to the locally installed version. If a newer
 * release exists, a single chat message tells the user the mod is outdated.
 *
 * To avoid pestering, it writes a small state file
 * (`sexmod/.update_check_state`) after a successful
 * check; it only re-checks once per local version + once per latest release.
 * Network failures are silent (offline play) — no chat spam.
 *
 * Version parsing: accepts `v1.2.3`, `1.2.3`, `1.2.3-SNAPSHOT`,
 * `1.2 (1)` (the local jar layout) by extracting digit triples; compares
 * numerically.
 */
object UpdateChecker {
    const val REPO: String = "ReverseEngineeringEnthusiasts/Jenny-Mod-Fapcraft"
    const val RELEASES_URL: String = "https://api.github.com/repos/$REPO/releases/latest"

    /** @return newest release tag (e.g. "1.2.4") or null on any failure/offline. */
    @JvmStatic
    fun fetchLatestTag(): String? {
        val rel = fetchLatestRelease() ?: return null
        var tag: String? = if (rel.has("tag_name")) rel.get("tag_name").asString else null
        if (tag == null && rel.has("name")) tag = rel.get("name").asString
        return tag
    }

    /** @return newest release body (changelog) or null. */
    @JvmStatic
    fun fetchLatestBody(): String? {
        val rel = fetchLatestRelease()
        if (rel == null || !rel.has("body")) return null
        val body: String? = rel.get("body").asString
        return body?.trim()
    }

    private fun fetchLatestRelease(): JsonObject? {
        try {
            val conn = URI(RELEASES_URL).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Fapcraft-1.21.1/" + BuildInfo.VERSION + " (update checker)")
            val code = conn.responseCode
            if (code != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { r ->
                val sb = StringBuilder()
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line)
                }
                return Gson().fromJson(sb.toString(), JsonObject::class.java)
            }
        } catch (t: Throwable) {
            return null
        }
    }

    /** Numeric version parse: [major, minor, patch] from common tag formats. */
    @JvmStatic
    fun parseVersion(v: String?): IntArray {
        if (v == null) return intArrayOf(0, 0, 0)
        var s = v.trim().replaceFirst(Regex("^[vV]"), "").lowercase(Locale.ROOT)
        // strip -SNAPSHOT / -beta etc.
        val dash = s.indexOf('-')
        if (dash >= 0) s = s.substring(0, dash)
        val digits = StringBuilder()
        val nums = ArrayList<Int>()
        for (i in 0 until s.length) {
            val c = s[i]
            if (Character.isDigit(c)) {
                digits.append(c)
            } else if (digits.isNotEmpty()) {
                nums.add(Integer.parseInt(digits.toString()))
                digits.setLength(0)
            }
        }
        if (digits.isNotEmpty()) nums.add(Integer.parseInt(digits.toString()))
        while (nums.size < 3) nums.add(0)
        return intArrayOf(nums[0], nums[1], nums[2])
    }

    @JvmStatic
    fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 3) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i])
        }
        return 0
    }

    /** Returns a short plain-text changelog snippet (first few meaningful lines). */
    @JvmStatic
    fun summarizeBody(body: String?, maxLines: Int): String? {
        if (body == null || body.trim().isEmpty()) return null
        val sb = StringBuilder()
        var lines = 0
        for (rawLine in body.split(Regex("\r?\n"))) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            // strip markdown bullets/headers/links roughly
            line = line.replace(Regex("^#{1,6}\\s+"), "")
            line = line.replace(Regex("^[-*+]\\s+"), "• ")
            line = line.replace(Regex("\\[(.+?)\\]\\([^)]+\\)"), "$1")
            line = line.replace(Regex("[*_`~]"), "")
            if (line.isEmpty()) continue
            if (line.length > 110) line = line.substring(0, 110) + "…"
            sb.append("  ").append(line).append("\n")
            if (++lines >= maxLines) break
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) null else out
    }

    /** Whether a newer release is available than `localVersion`. */
    @JvmStatic
    fun isOutdated(localVersion: String?, latestTag: String?): Boolean {
        if (localVersion == null || latestTag == null) return false
        return compare(parseVersion(localVersion), parseVersion(latestTag)) < 0
    }

    /** Build-aware: if the latest tag is "vN", compare the local build counter to N. */
    @JvmStatic
    fun isOutdatedBuild(localBuild: Int, latestTag: String?): Boolean {
        if (latestTag == null) return false
        val s = latestTag.trim().lowercase(Locale.ROOT)
        // match v7, build 7, 7
        val m = Pattern.compile("^v?(\\d+)$").matcher(s)
        if (m.find()) {
            return try {
                val latest = Integer.parseInt(m.group(1))
                localBuild < latest
            } catch (e: NumberFormatException) {
                false
            }
        }
        // not build-shaped: fall back to semver-ish, treat localBuild as major
        return isOutdated(localBuild.toString(), latestTag)
    }

    /* ---------------- notify-once state ---------------- */

    private fun stateFile(): File = File("sexmod/.update_check_state")

    /** True if we already notified for this local version + latest SHA (or latest is unknown). */
    @JvmStatic
    fun alreadyNotified(localVersion: String, latestTag: String?): Boolean {
        try {
            val f = stateFile()
            if (!f.isFile) return false
            val r = BufferedReader(FileReader(f))
            while (true) {
                val line = r.readLine() ?: break
                if (line.trim() == "$localVersion|$latestTag") return true
            }
            r.close()
        } catch (t: Throwable) {
            // ignored: a missing seen-list simply means no version has been seen yet
        }
        return false
    }

    @JvmStatic
    fun markNotified(localVersion: String, latestTag: String?) {
        try {
            val f = stateFile()
            f.parentFile?.mkdirs()
            val lines = ArrayList<String>()
            if (f.isFile) {
                val r = BufferedReader(FileReader(f))
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.trim().isNotEmpty()) lines.add(l.trim())
                }
                r.close()
            }
            val entry = localVersion + "|" + (latestTag ?: "")
            if (!lines.contains(entry)) lines.add(entry)
            Files.write(f.toPath(), (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            // ignored: failing to record a seen version is harmless
        }
    }
}
