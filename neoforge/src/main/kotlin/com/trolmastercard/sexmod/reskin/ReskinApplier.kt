package com.trolmastercard.sexmod.reskin

import java.util.Locale

/**
 * **Role.** MC-free plan builder: decides, per archive entry, where it
 * belongs in the game's resource namespace.
 *
 * A resourcepack-layout pack (`assets/sexmod/geo/....geo.json`,
 * `assets/sexmod/textures/entity/....png`, `assets/sexmod/animations/....json`)
 * is applied by overriding the SAME `sexmod:` resource
 * locations the girl models already use — that's how a reskin replaces the
 * girl's appearance without touching any file on disk.
 *
 * Anything else (lang, sounds, structures, gui textures, pack.png, legacy
 * `.cfg` wear-model files) is NOT part of a reskin and is skipped.
 */
object ReskinApplier {
    enum class Kind { GEO, TEXTURE, ANIMATION }

    /** One apply operation: read [relName] from the archive, override RL [rlPath]. */
    open class Op(
        @JvmField val relName: String, // entry name inside the archive
        @JvmField val rlNs: String, // resource namespace (always "sexmod" for these packs)
        @JvmField val rlPath: String, // path after "assets/sexmod/" (the sexmod: RL path)
        @JvmField val kind: Kind,
        @JvmField val girl: String?,
    ) {
        override fun toString(): String = "$kind $rlNs:$rlPath <- $relName"
    }

    /** The full apply plan for an entry (empty for non-reskin packs). */
    @JvmStatic
    fun plan(e: ReskinEntry): List<Op> {
        val ops = ArrayList<Op>()
        for (fi in e.files) {
            val n = fi.name.replace('\\', '/')
            val low = n.lowercase(Locale.ROOT)

            // resourcepack-layout paths: assets/sexmod/ may sit at ANY depth
            // (top-level "Elliot Pajama.zip" OR "SUKUNA/assets/sexmod/..." after
            // an extra folder). Find the anchor, then slice everything after it.
            val prefix = "assets/sexmod/"
            val anchor = low.indexOf(prefix)
            if (anchor < 0) continue

            val rest = n.substring(anchor + prefix.length)
            val lowerRest = rest.lowercase(Locale.ROOT)
            val girl = ReskinScanner.detectGirl(low)

            // first path segment decides the type (geo/textures/animations)
            var firstSeg = lowerRest
            val slash = lowerRest.indexOf('/')
            if (slash >= 0) firstSeg = lowerRest.substring(0, slash)

            if (firstSeg == "geo" && (lowerRest.endsWith(".geo.json") || lowerRest.endsWith(".json"))) {
                ops.add(Op(fi.name, "sexmod", rest, Kind.GEO, girl))
            } else if (firstSeg == "textures" && (lowerRest.endsWith(".png") || lowerRest.endsWith(".jpg"))) {
                ops.add(Op(fi.name, "sexmod", rest, Kind.TEXTURE, girl))
            } else if (firstSeg == "animations" && lowerRest.endsWith(".json")) {
                ops.add(Op(fi.name, "sexmod", rest, Kind.ANIMATION, girl))
            }
            // lang/sounds/structures/pack.png/gui etc: skipped for reskins
        }
        return ops
    }

    /**
     * Decoy/placeholder detection: some packs ship fake 2048-byte JSON files
     * (identifier `geometry.fake_NN` and a base64 `_pad` tail) to
     * pollute the folder with junk — they must be skipped, not applied or
     * reported as errors. Real model files never contain these markers.
     */
    @JvmStatic
    fun isDecoy(json: String?): Boolean {
        if (json == null) return false
        val low = json.lowercase(Locale.ROOT)
        return low.contains("geometry.fake") ||
            low.contains("\"fake") ||
            low.contains("_pad")
    }

    /**
     * Sanitizes a geo JSON before the geckolib parse:
     * - returns null for decoys and for non-geo JSON (e.g. animation files
     *   that were misplaced into the geo/ folder — they lack the
     *   `"minecraft:geometry"` root key);
     * - normalizes `format_version` to "1.12.0" so old packs (1.8.0
     *   etc.) keep parsing with geckolib.
     */
    @JvmStatic
    fun prepareGeoJson(json: String?): String? {
        if (json == null || isDecoy(json)) return null
        val low = json.lowercase(Locale.ROOT)
        if (!low.contains("\"minecraft:geometry\"")) return null
        return json.replace(Regex("(?i)\"format_version\"\\s*:\\s*\"[^\"]*\""), "\"format_version\": \"1.12.0\"")
    }

    /** True if the JSON is a usable animation file (has an "animations" root, not a decoy). */
    @JvmStatic
    fun isAnimationJson(json: String?): Boolean {
        if (json == null || isDecoy(json)) return false
        return json.lowercase(Locale.ROOT).contains("\"animations\"")
    }
}
