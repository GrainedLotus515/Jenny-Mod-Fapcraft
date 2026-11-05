package com.trolmastercard.sexmod

import net.neoforged.fml.ModList
import java.util.Properties

/**
 * **Role.** Runtime view of the mod version. In the 1.12 build `pom.xml`'s
 * `<version>` was filtered into `sexmod-build.properties` on the classpath and
 * read once here, so a version bump was one edit in `pom.xml`. The NeoForge
 * build no longer ships that file (the version lives in `neoforge.mods.toml`),
 * so [load] also falls back to the loaded mod container's version.
 *
 * **Pitfall.** [DEFAULT_VERSION] must stay a compile-time constant. It is the
 * fallback when neither the resource nor the mod container is available (IDE
 * run, plain `javac`), which is why a value still containing `${` is treated as
 * missing.
 */
object BuildInfo {
    /** Fallback version for unfiltered builds; keep in step with pom.xml. */
    const val DEFAULT_VERSION = "1.1.1"

    private const val RESOURCE = "/sexmod-build.properties"

    /** Effective mod version: filtered pom version, else the mod container version, else [DEFAULT_VERSION]. */
    @JvmField
    val VERSION: String = load()

    @JvmStatic
    private fun load(): String {
        try {
            BuildInfo::class.java.getResourceAsStream(RESOURCE).use { stream ->
                if (stream == null) return fromModList()
                val props = Properties()
                props.load(stream)
                var v: String = props.getProperty("version") ?: return fromModList()
                v = v.trim()
                // unfiltered resource still carries the placeholder
                if (v.isEmpty() || v.contains("\${")) return fromModList()
                return v
            }
        } catch (t: Throwable) {
            return DEFAULT_VERSION
        }
    }

    @JvmStatic
    private fun fromModList(): String = try {
        ModList.get()?.getModContainerById(Main.MOD_ID)?.map { it.modInfo.version.toString() }?.orElse(DEFAULT_VERSION)
            ?: DEFAULT_VERSION
    } catch (t: Throwable) {
        DEFAULT_VERSION
    }
}
