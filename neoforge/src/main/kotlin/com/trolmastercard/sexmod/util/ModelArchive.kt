package com.trolmastercard.sexmod.util

import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.Level
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * **Role.** The archive ("zip model") side of custom-model registration for
 * [ServerWhitelistManager]: spots `.zip` packs as virtual model
 * names, checks that a zip contains a usable geo/texture pair, and registers a
 * model by reading `.cfg`/`.png`/`.geo.json` straight out of
 * the archive (a missing cfg is generated from the geo bones).
 *
 * **Pitfalls.** Zip entries are matched case-insensitively by suffix — the
 * first hit wins, so a pack can shadow files of the same name. The generated
 * cfg is written to a temp file and deleted straight after parsing, so a
 * `ModelData` that holds that path is only valid inside the call. The
 * texture/geo branches are client-only; with `textureFlag == false`
 * (dedicated server) only the model name is registered.
 *
 * Port notes: COMMON object. The client-only texture/geo work goes through
 * [ModelResources] (only reached with `textureFlag == true`, i.e. on the client).
 */
object ModelArchive {
    @JvmStatic
    fun getPartName(modelName: String, zipFileMap: Map<String, File>, group: String): String {
        if (zipFileMap.containsKey(modelName)) {
            val zip = zipFileMap[modelName]!!
            try {
                ZipFile(zip).use { zf ->
                    var hasGeo = false
                    var hasPng = false
                    var hasCfg = false
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val n = en.nextElement().name.lowercase()
                        if (n.endsWith(modelName.lowercase() + ".geo.json") || n.endsWith("jennydressed.geo.json") || n.endsWith("jennynude.geo.json")) hasGeo = true
                        if (n.endsWith(modelName.lowercase() + ".png") || n.endsWith("jenny.png")) hasPng = true
                        if (n.endsWith(modelName.lowercase() + ".cfg")) hasCfg = true
                    }
                    // cfg auto-generated if missing for full-mod zips
                    if (!hasGeo) return String.format("couldn't find model File for '%s' inside zip '%s'.", modelName, zip.absolutePath)
                    if (!hasPng) return String.format("couldn't find texture File for '%s' inside zip '%s'.", modelName, zip.absolutePath)
                    return ""
                }
            } catch (e: IOException) {
                return String.format("couldn't read zip for '%s': %s", modelName, e.message)
            }
        }

        val path = String.format("%s/%s", group, modelName)
        val geoFile = File(String.format("%s/%s.geo.json", path, modelName))
        val textureFile = File(String.format("%s/%s.png", path, modelName))
        val cfgFile = File(String.format("%s/%s.cfg", path, modelName))
        return if (!geoFile.exists()) {
            String.format("couldn't find model File for '%s'. It should have been at '%s'. Are you sure it exists?", modelName, geoFile.absolutePath)
        } else if (!textureFile.exists()) {
            String.format("couldn't find texture File for '%s'. It should have been at '%s'. Are you sure it exists?", modelName, textureFile.absolutePath)
        } else {
            if (!cfgFile.exists())
                String.format("couldn't find cfg File for '%s'. It should have been at '%s'. Are you sure it exists?", modelName, cfgFile.absolutePath)
            else
                ""
        }
    }

    /**
     * Registers a zip-backed model. Returns `""` on success or the
     * user-facing error to surface, exactly like
     * [ServerWhitelistManager.registerModel].
     */
    @JvmStatic
    fun registerFromZip(
        modelName: String,
        zipFileMap: Map<String, File>,
        modelDataMap: MutableMap<String, ServerWhitelistManager.ModelData>,
        textureFlag: Boolean,
    ): String {
        val zipFile = zipFileMap[modelName]!!
        try {
            ZipFile(zipFile).use { zf ->
                val cfgEntry = findZipEntry(zf, "$modelName.cfg")
                var pngEntry = findZipEntry(zf, "$modelName.png")
                if (pngEntry == null) pngEntry = findZipEntry(zf, "jenny.png")
                var geoEntry = findZipEntry(zf, "$modelName.geo.json")
                if (geoEntry == null) geoEntry = findZipEntry(zf, "jennydressed.geo.json")
                if (geoEntry == null) geoEntry = findZipEntry(zf, "jennynude.geo.json")
                val modelData: ServerWhitelistManager.ModelData
                if (cfgEntry != null) {
                    zf.getInputStream(cfgEntry).use { stream ->
                        val props = Properties()
                        props.load(stream)
                        val tmp = File.createTempFile("model", ".cfg")
                        FileOutputStream(tmp).use { fos -> props.store(fos, null) }
                        modelData = ServerWhitelistManager.ModelData(tmp, modelName)
                        tmp.delete()
                    }
                } else {
                    // auto-detect full outfit vs head accessory from geo bones
                    var wear = "HEAD"
                    var extra = ""
                    if (geoEntry != null) {
                        try {
                            zf.getInputStream(geoEntry).use { gis ->
                                BufferedReader(InputStreamReader(gis)).use { gbr ->
                                    val gsb = StringBuilder()
                                    while (true) {
                                        val gl = gbr.readLine() ?: break
                                        gsb.append(gl)
                                    }
                                    val gj = gsb.toString().lowercase()
                                    if (gj.contains("\"body\"") || gj.contains("upperbody") || gj.contains("\"hip\"")) {
                                        wear = "CUSTOM_BONE"
                                        extra = "custom_bone=body\n"
                                    } else if (gj.contains("custom")) {
                                        wear = "CUSTOM_BONE"
                                        extra = "custom_bone=body\n"
                                    }
                                }
                            }
                        } catch (ignored2: Exception) {
                            // ignored: unparsable geo json keeps the default HEAD wear
                        }
                    }
                    val scale = if (wear == "GIRL_SPECIFIC" || wear == "CUSTOM_BONE") 0.35f else 1.0f
                    val yOff = if (wear == "GIRL_SPECIFIC" || wear == "CUSTOM_BONE") -5.0f else 0.0f
                    val hide = if (wear == "CUSTOM_BONE" && extra.contains("body")) "body,upperBody,torso,top,hipbend,head,armL,armR,legL,legR" else ""
                    val tmp = File.createTempFile("model", ".cfg")
                    FileWriter(tmp).use { w ->
                        w.write(
                            "wear_type=" + wear + "\n" + extra + "which_girls=JENNY\nwhich_lighting=DEFAULT\nauthor=auto\nbones_to_hide=" + hide +
                                "\nenable_when_nude=yes\ngui_size_factor=" + scale + "\ngui_vertical_positioning=" + yOff + "\nversion=1.0\n"
                        )
                    }
                    modelData = ServerWhitelistManager.ModelData(tmp, modelName)
                    tmp.delete()
                }
                if (modelData.errorMessage != null) return modelData.errorMessage!!
                var textureLoc: ResourceLocation? = null
                if (textureFlag) {
                    if (pngEntry == null) return String.format("The texture for the custom model '%s' couldn't be found inside zip '%s'", modelName, zipFile.name)
                    try {
                        zf.getInputStream(pngEntry).use { stream ->
                            textureLoc = ModelResources.loadTexture(modelName, stream)
                        }
                    } catch (e: Exception) {
                        return String.format("The texture for the custom model '%s' inside zip '%s' appears to be corrupted. %s", modelName, zipFile.name, e.message)
                    }
                }
                if (geoEntry == null) return String.format("The geo model for the custom model '%s' couldn't be found inside zip '%s'", modelName, zipFile.name)
                if (textureFlag) {
                    val raw: String
                    try {
                        zf.getInputStream(geoEntry).use { stream ->
                            BufferedReader(InputStreamReader(stream)).use { br ->
                                val sb = StringBuilder()
                                while (true) {
                                    val line = br.readLine() ?: break
                                    sb.append(line)
                                }
                                raw = sb.toString()
                            }
                        }
                    } catch (e: IOException) {
                        return String.format("The geo model for the custom model '%s' inside zip '%s' appears to be corrupted.", modelName, zipFile.name)
                    }
                    try {
                        val geoRes = ServerWhitelistManager.getGeoResourceLocation(modelName)
                        ModelResources.registerGeoModelFromJson(geoRes, raw)
                        modelData.setFallbackTexture(geoRes)
                        modelData.setTextureLocation(textureLoc)
                        modelDataMap[modelName] = modelData
                        ModelNotifications.logError(Level.DEBUG, String.format("successfully registered custom model '%s' from zip", modelName))
                        return ""
                    } catch (e: Exception) {
                        return String.format("The geo model for the custom model '%s' inside zip '%s' appears to be corrupted.", modelName, zipFile.name)
                    }
                }
                modelData.setFallbackTexture(ServerWhitelistManager.getGeoResourceLocation(modelName))
                modelData.setTextureLocation(textureLoc)
                modelDataMap[modelName] = modelData
                ModelNotifications.logError(Level.DEBUG, String.format("successfully registered custom model '%s' from zip", modelName))
                return ""
            }
        } catch (e: IOException) {
            return String.format("couldn't read zip for '%s': %s", modelName, e.message)
        }
    }

    private fun findZipEntry(zf: ZipFile, name: String): ZipEntry? {
        val low = name.lowercase()
        val en = zf.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val ename = e.name.lowercase()
            if (ename.endsWith("/$low") || ename == low || ename.endsWith(low)) return e
        }
        return null
    }
}
