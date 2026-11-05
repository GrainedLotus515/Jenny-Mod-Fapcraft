package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.api.LightingType
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BoneType
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UnknownPacket
import com.trolmastercard.sexmod.reskin.ReskinEntry
import com.trolmastercard.sexmod.reskin.ReskinScanner
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.ClientChatEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import org.apache.logging.log4j.Level
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * **Role.** Custom-model management on both sides.
 *
 * CLIENT: registers the whitelist file (`sexmod/custom_models/whitelisted_servers.txt`),
 * decides whether a server may push models ([isGlobalRenderingDisabled]
 * — the current server IP must be whitelisted), and loads custom models
 * (`.cfg` + `.png` + `.geo.json`) from
 * `sexmod/custom_models/<server>/` into GeckoLib
 * ([registerModel]), exposing them via
 * [getModelDataForGirl], [getModelParts],
 * [getModelResource] etc.
 *
 * SERVER: `loadCustomModels(false)` registers model *names* (no textures/
 * geo) so `DownloadServerModelPacket` can serve the files.
 *
 * **Flow.** [ChatHandler] triggers a reload on server connect / world
 * join (via [UnknownPacket]) and a full cache clear on disconnect; the
 * editor wand and model-code commands read the registered [ModelData].
 *
 * **Pitfalls.** `"cross"` is a reserved model name (internal fallback)
 * and `#`/spaces are illegal in model names — validation lives in
 * [ModelData]'s constructor. [getCurrentGroup] differs per side
 * (client: per-server folder; server: shared folder).
 *
 * Port notes: COMMON object; client-only work lives in the nested [Client]
 * object and in [ModelResources] (PORTING.md §2.3). `Main.proxy instanceof
 * ClientProxy` is `FMLEnvironment.dist.isClient`. Baked custom geo models are
 * kept in [ModelResources.CUSTOM_GEO_MODELS] (GeckoLib 4 caches are rebuilt on
 * reload); their ids are `sexmod:<sanitised name>model` because 1.21 resource
 * locations reject upper case ([getGeoResourceLocation]).
 */
object ServerWhitelistManager {
    const val CUSTOM_MODELS_DIR: String = "sexmod/custom_models"
    const val WHITELIST_FILE: String = "sexmod/custom_models/whitelisted_servers.txt"
    const val CUSTOM_MODELS_KEY: String = "sexmod_custom_models"

    @JvmField
    var modelDataMap: MutableMap<String, ModelData> = HashMap()

    @JvmField
    var zipFileMap: MutableMap<String, File> = HashMap()

    @JvmField
    var isGlobalRenderingDisabled: Boolean = false

    @JvmField
    var isLoaded: Boolean = false

    /** Total archives found by the last recursive pack scan (0 if none/not scanned). */
    @JvmField
    var recursivePackCount: Int = 0

    @JvmStatic
    fun getModelDataMap(): Map<String, ModelData> = modelDataMap

    @JvmStatic
    fun isModelDisabled(modelName: String): Boolean = modelDataMap[modelName] != null

    @JvmStatic
    fun getModelCount(disableRendering: Boolean): Int {
        setGlobalRenderingDisabled(disableRendering)
        return loadCustomModels(disableRendering)
    }

    @JvmStatic
    fun logError(level: Level, message: String) {
        ModelNotifications.logError(level, message)
    }

    @JvmStatic
    fun setGlobalRenderingDisabled(disabled: Boolean) {
        if (disabled) {
            syncModelData()
        }

        modelDataMap.clear()
    }

    @JvmStatic
    fun reloadCustomModels() {
        PacketHandler.networkWrapper.sendToServer(UnknownPacket())
    }

    /** CLIENT only: whether the current server is whitelisted (name kept from 1.12; it reads inverted). */
    @JvmStatic
    fun isGlobalRenderingDisabled(): Boolean {
        val customModelsKey = getCustomModelsKey()
        return if (customModelsKey == null) false else isModelWhitelisted(customModelsKey)
    }

    @JvmStatic
    fun initWhitelistFile(serverName: String) {
        ServerWhitelistFile.addServer(serverName)
    }

    @JvmStatic
    fun isModelWhitelisted(serverName: String): Boolean = ServerWhitelistFile.contains(serverName)

    @JvmStatic
    fun loadWhitelistedServers(): HashSet<String> = ServerWhitelistFile.read()

    @JvmStatic
    fun getModelZOffset(modelName: String): Float {
        val modelData = modelDataMap[modelName]
        return modelData?.getZOffset() ?: 0.0f
    }

    /** CLIENT only. */
    @JvmStatic
    fun syncModelData() {
        ModelResources.releaseAll(modelDataMap)
    }

    /** CLIENT only. */
    @JvmStatic
    fun logInfo(level: Level, message: String) {
        ModelNotifications.logInfo(level, message)
    }

    @JvmStatic
    fun getCurrentGroup(): String = if (FMLEnvironment.dist.isClient) getGlobalModelOverride() else "sexmod_custom_models"

    /** Local mod version, e.g. "1.1.1". */
    @JvmStatic
    fun localVersion(): String = Reference.MOD_VERSION

    /** Local build number for the release stream (matches GitHub "Fapcraft Build N" tags). */
    @JvmStatic
    fun localBuild(): Int = 8

    /**
     * Fired on the first world join: checks GitHub releases once per session and
     * shows a chat message if a newer release is available (state file prevents
     * re-pestering across sessions for the same version+release). CLIENT only.
     */
    @JvmStatic
    fun checkForUpdatesOnce() {
        UpdateNotice.checkOnce()
    }

    /** CLIENT only. */
    @JvmStatic
    fun getGlobalModelOverride(): String {
        val customModelsKey = getCustomModelsKey()
        return if (customModelsKey == null) "sexmod/custom_models/singleplayer" else "sexmod/custom_models/$customModelsKey"
    }

    /** CLIENT only: the current server's address without port, or null in singleplayer. */
    @JvmStatic
    fun getCustomModelsKey(): String? = Client.getCustomModelsKey()

    @JvmStatic
    fun loadCustomModels(global: Boolean): Int {
        logError(Level.INFO, "loading up custom models...")
        val group = getCurrentGroup()
        val dir = File(group)
        dir.mkdirs()
        var modelNames: Array<String>? = dir.list { subDir, subName -> File(subDir, subName).isDirectory }
        // collect zips as virtual models (read directly, no extraction)
        val zipModels = HashSet<String>()
        try {
            val zips = dir.listFiles { _, n -> n.lowercase().endsWith(".zip") }
            if (zips != null) for (z in zips) {
                val base = z.name.substring(0, z.name.length - 4)
                var safe = base.replace(Regex("[\\s#$]+"), "_").replace(Regex("[^a-zA-Z0-9_\\-]"), "")
                if (safe.isEmpty()) safe = "model"
                if (File(dir, safe).exists()) continue // dir takes precedence
                zipModels.add(safe)
                // map zip file for later direct read (store in temp map)
                zipFileMap[safe] = z
            }
        } catch (ignored: Exception) {
            // ignored: a broken zip entry just hides that model
        }
        // merge zip virtual names with directory names
        if (zipModels.isNotEmpty()) {
            val merged = HashSet<String>(listOf(*(modelNames ?: arrayOf())))
            merged.addAll(zipModels)
            modelNames = merged.toTypedArray()
        }
        if (modelNames == null) {
            logError(
                Level.ERROR,
                String.format(
                    "Something is wrong with the custom models folder at '%s'. Check if it exists, if not - make the directory yourself because Minecraft cannot do it itself for some reason",
                    dir.absolutePath
                )
            )
            return -1
        }

        logError(Level.INFO, String.format("found %s custom model(s)", modelNames.size))
        var count = 0

        for (modelName in modelNames) {
            var error = getPartName(modelName, group)
            if ("" != error) {
                // A single broken/container entry (e.g. the "Custom-Models" folder
                // that just HOLDS sub-packs, or an animation-only zip) must NOT kill
                // the whole load. Skip it and keep registering the valid models.
                val probeDir = File(group, modelName)
                val looksLikeContainer = probeDir.isDirectory &&
                    !File(probeDir, "$modelName.cfg").isFile &&
                    !File(probeDir, "$modelName.geo.json").isFile
                if (looksLikeContainer) {
                    logError(Level.DEBUG, String.format("skipping folder '%s' (no model files inside - container folder)", modelName))
                    continue
                }
                logError(Level.DEBUG, String.format("skipping custom model '%s': %s", modelName, error))
                continue
            }

            error = registerModel(modelName, group, global)
            if ("" != error) {
                logError(Level.DEBUG, String.format("skipping custom model '%s': %s", modelName, error))
                continue
            }

            count++
        }

        logError(Level.DEBUG, String.format("successfully registered %s custom models", count))
        // Recursive discovery: the legacy loader only reads IMMEDIATE children, but
        // real installs nest packs arbitrarily deep (Custom-Models/...). Scan the
        // whole tree (zip central dir + rar headers only, no decompression) and
        // report what's actually available so the reload reflects reality.
        try {
            val packs: List<ReskinEntry> = ReskinScanner().scan(dir)
            val total = packs.size
            var zipN = 0
            var rarN = 0
            var reskins = 0
            for (pe in packs) {
                if (pe.kind == ReskinEntry.Kind.ZIP) zipN++ else rarN++
                if (pe.isReskin()) reskins++
            }
            recursivePackCount = total
            logError(
                Level.INFO, String.format(
                    "recursive pack scan: %d pack(s) found (any depth): %d zip, %d rar; %d resourcepack reskins available (apply via the wardrobe's Reskins button)",
                    total, zipN, rarN, reskins
                )
            )
        } catch (t: Throwable) {
            logError(Level.DEBUG, "recursive pack scan failed: " + t.message)
        }
        isLoaded = true
        return 0
    }

    @JvmStatic
    fun getPartName(modelName: String, group: String): String = ModelArchive.getPartName(modelName, zipFileMap, group)

    /** CLIENT only. */
    @JvmStatic
    @Throws(IOException::class)
    fun loadTexture(name: String, file: File): ResourceLocation = ModelResources.loadTexture(name, file)

    /** 1.12 `new ResourceLocation("sexmod", modelName + "Model")`, lowercased/sanitised for 1.21. */
    @JvmStatic
    fun getGeoResourceLocation(modelName: String): ResourceLocation = Main.rl(sanitizeResourcePath(modelName + "Model"))

    /** 1.21 resource paths only allow `[a-z0-9/._-]`. */
    @JvmStatic
    fun sanitizeResourcePath(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9/._-]"), "_")

    @JvmStatic
    fun registerModel(modelName: String, group: String, textureFlag: Boolean): String {
        if (modelDataMap[modelName] != null) {
            return String.format("already registered '%s'... honestly, unsure how this could happen lol", modelName)
        }
        // zip-direct path: read .cfg/.png/.geo.json straight from the zip
        if (zipFileMap.containsKey(modelName)) {
            return ModelArchive.registerFromZip(modelName, zipFileMap, modelDataMap, textureFlag)
        }
        val modelDir = String.format("%s/%s/", group, modelName)
        val cfgPath = "$modelDir$modelName.cfg"
        val cfgFile = File(cfgPath)
        if (!cfgFile.exists()) {
            return String.format("couldn't find cfg File for '%s'. It should have been at '%s'. Are you sure it exists?", modelName, cfgPath)
        }

        val modelData = ModelData(cfgFile, modelName)
        if (modelData.errorMessage != null) {
            return modelData.errorMessage!!
        }

        val texturePath = "$modelDir$modelName.png"
        val textureFile = File(texturePath)
        if (!textureFile.exists()) {
            return String.format("The texture for the custom model '%s' couldn't be found at '%s' are you sure it exists?", modelName, texturePath)
        }

        var textureLoc: ResourceLocation? = null
        if (textureFlag) {
            try {
                textureLoc = loadTexture(modelName, textureFile)
            } catch (ioException: IOException) {
                return String.format("The texture for the custom model '%s' at '%s' appears to be corrupted. Try making a new one", modelName, texturePath)
            } catch (exception: Exception) {
                return String.format(
                    "Couldn't load the texture for the custom model '%s' at '%s'. Maybe try increasing the amount of RAM of ur Minecraft client", modelName, textureFile
                )
            }
        }

        val geoResource = getGeoResourceLocation(modelName)
        val geoPath = "$modelDir$modelName.geo.json"
        val geoFile = File(geoPath)
        if (!geoFile.exists()) {
            return String.format("The geo model for the custom model '%s' couldn't be found at '%s' are you sure it exists?", modelName, geoPath)
        }

        if (textureFlag) {
            val error = Client.registerGeoModel(modelName, geoResource, geoFile, geoPath)
            if (error != null) {
                return error
            }
        }

        if (textureFlag) {
            modelData.setFallbackTexture(geoResource)
            modelData.setTextureLocation(textureLoc)
        }

        modelDataMap[modelName] = modelData
        logError(Level.DEBUG, String.format("successfully registered custom model '%s'", modelName))
        return ""
    }

    @JvmStatic
    fun getModelResource(modelName: String): ResourceLocation? {
        val modelData = modelDataMap[modelName]
        if (modelData == null) {
            if (modelName != "cross") {
                Main.LOGGER.warn("The custom model for '{}', hasn't been registered, but gamers tried to use it anyways. Crash is imminent", modelName)
            }

            return null
        } else {
            return modelData.getFallbackTexture()
        }
    }

    @JvmStatic
    fun getModelTexture(modelName: String): ResourceLocation? {
        val modelData = modelDataMap[modelName]
        if (modelData == null) {
            if (modelName != "cross") {
                Main.LOGGER.warn("The custom texture for '{}', hasn't been registered, but gamers tried to use it anyways. Crash is imminent", modelName)
            }

            return null
        } else {
            return modelData.getTextureLocation()
        }
    }

    /** CLIENT only: the baked custom model (1.12 read the GeckoLib cache). */
    @JvmStatic
    fun getGeoModel(modelName: String): BakedGeoModel? = ModelResources.getCustomBakedModel(getModelResource(modelName))

    @JvmStatic
    fun getBoneType(modelName: String): BoneType {
        val modelData = modelDataMap[modelName]
        if (modelData == null) {
            if (modelName != "cross") {
                Main.LOGGER.warn("The ClothingType for '{}', hasn't been registered, but gamers tried to use it anyways. Crash is imminent", modelName)
            }

            return BoneType.HEAD
        } else {
            return modelData.boneType!!
        }
    }

    @JvmStatic
    fun getAllowedNpcTypes(modelName: String): HashSet<NpcType>? {
        val modelData = modelDataMap[modelName]
        if (modelData == null) {
            if (modelName != "cross") {
                Main.LOGGER.warn("The HashSet<GirlType> for '{}', hasn't been registered, but gamers tried to use it anyways. Crash is imminent", modelName)
            }

            return null
        } else {
            return modelData.allowedNpcTypes
        }
    }

    @JvmStatic
    fun getCustomPartBones(modelName: String): HashSet<String> {
        val modelData = modelDataMap[modelName]
        if (modelData == null) {
            if (modelName != "cross") {
                Main.LOGGER.warn("The HashSet<String> for '{}', hasn't been registered, but gamers tried to use it anyways. Crash is imminent", modelName)
            }

            return HashSet()
        } else {
            return modelData.customPartBones
        }
    }

    @JvmStatic
    fun getModelCode(modelName: String): String {
        val modelData = modelDataMap[modelName]
        if (modelData == null) {
            if (modelName != "cross") {
                Main.LOGGER.warn("The author for '{}', hasn't been registered, but gamers tried to use it anyways. Crash is imminent", modelName)
            }

            return ""
        } else {
            return modelData.modelCode ?: ""
        }
    }

    @JvmStatic
    fun getModelDataForGirl(modelName: String): ModelData? = modelDataMap[modelName]

    @JvmStatic
    fun getModelParts(girl: BaseGirlEntity?): HashMap<BoneType, MutableList<String>> {
        val partsMap = HashMap<BoneType, MutableList<String>>()

        for (boneType in BoneType.entries) {
            partsMap[boneType] = ArrayList()
        }

        for (entry in modelDataMap.entries) {
            val modelName = entry.key
            val modelData = entry.value
            val boneType2 = modelData.boneType
            val models = partsMap[boneType2] ?: continue
            if (modelData.allowedNpcTypes.isEmpty() || modelData.allowedNpcTypes.contains(NpcType.getNpcType(girl))) {
                models.add(modelName)
                partsMap[boneType2!!] = models
            }
        }

        return partsMap
    }

    @JvmStatic
    fun getModelScales(): HashMap<String, Float> {
        val scales = HashMap<String, Float>()

        for (entry in getModelDataMap().entries) {
            scales[entry.key] = entry.value.getZOffset()
        }

        return scales
    }

    /**
     * **Role.** Client-side events for the custom-model lifecycle: the
     * `id` chat command (girl UUID lookup), model (re)load on server connect,
     * the model-download request when first joining a whitelisted server, and cache
     * cleanup on disconnect. CLIENT-ONLY class.
     */
    open class ChatHandler {
        @JvmField
        var hasSentId: Boolean = false

        @SubscribeEvent
        open fun onClientChat(event: ClientChatEvent) {
            val message = event.originalMessage
            if ("id" == message) {
                val player = Minecraft.getInstance().player ?: return
                val nearbyGirls = player.level().getEntitiesOfClass(BaseGirlEntity::class.java, player.boundingBox.inflate(10.0))
                var closestGirl: BaseGirlEntity? = null

                for (girl in nearbyGirls) {
                    if (closestGirl == null) {
                        closestGirl = girl
                    } else if (player.distanceTo(girl) < player.distanceTo(closestGirl)) {
                        closestGirl = girl
                    }
                }

                if (closestGirl != null) {
                    player.displayClientMessage(Component.literal(closestGirl.getGirlId().toString()), false)
                    event.isCanceled = true
                }
            }
        }

        @SubscribeEvent
        open fun onServerConnect(event: ClientPlayerNetworkEvent.LoggingIn) {
            val minecraft = Minecraft.getInstance()
            minecraft.execute { loadCustomModels(true) }
            this.hasSentId = false
        }

        @SubscribeEvent
        open fun onEntityJoinWorld(event: EntityJoinLevelEvent) {
            if (event.entity == Minecraft.getInstance().player) {
                if (!this.hasSentId) {
                    this.hasSentId = true
                    if (isGlobalRenderingDisabled()) {
                        reloadCustomModels()
                    }
                    checkForUpdatesOnce()
                }
            }
        }

        @SubscribeEvent
        open fun onServerDisconnect(event: ClientPlayerNetworkEvent.LoggingOut) {
            Minecraft.getInstance().execute { setGlobalRenderingDisabled(true) }
            this.hasSentId = false
        }
    }

    /**
     * **Role.** Parsed representation of one custom model's `.cfg` file:
     * wear type ([BoneType]), allowed girls ([NpcType]s), lighting,
     * author/model-code, bones to hide, nude-mode enable, GUI scale/position and the
     * registered textures. The constructor validates every field and stores the
     * first problem in [errorMessage] (callers abort registration on a
     * non-null message).
     *
     * **Pitfall.** The cfg property names are part of the user-facing mod
     * format (docs/examples depend on them) — renaming a property breaks every
     * existing custom model.
     */
    open class ModelData(file: File, modelName: String) {
        @JvmField
        var boneType: BoneType? = null

        @JvmField
        var allowedNpcTypes: HashSet<NpcType> = HashSet()

        @JvmField
        var customPartBones: HashSet<String> = HashSet()

        @JvmField
        var modelCode: String? = null

        @JvmField
        var modelName: String? = null

        @JvmField
        var disabled: Boolean = false

        @JvmField
        var lightingType: LightingType? = null

        @JvmField
        var scale: Float = 1.0f

        @JvmField
        var xOffset: Float = 0.0f

        @JvmField
        var textureLocation: ResourceLocation? = null

        @JvmField
        var fallbackTexture: ResourceLocation? = null

        @JvmField
        var errorMessage: String? = null

        @JvmField
        var zOffset: Float = 0.0f

        init {
            ModelCfgParser.parse(file, modelName, this)
        }

        open fun getModelName(): String? = this.modelName

        open fun getLightingType(): LightingType? = this.lightingType

        open fun getXOffset(): Float = this.xOffset

        open fun getScale(): Float = this.scale

        open fun getBoneType(): BoneType? = this.boneType

        open fun getAllowedNpcTypes(): HashSet<NpcType> = this.allowedNpcTypes

        open fun getModelCode(): String? = this.modelCode

        open fun isDisabled(): Boolean = this.disabled

        open fun getCustomPartBones(): HashSet<String> = this.customPartBones

        open fun getTextureLocation(): ResourceLocation? = this.textureLocation

        open fun setTextureLocation(textureLoc: ResourceLocation?) {
            this.textureLocation = textureLoc
        }

        open fun getFallbackTexture(): ResourceLocation? = this.fallbackTexture

        open fun setFallbackTexture(fallback: ResourceLocation?) {
            this.fallbackTexture = fallback
        }

        open fun getZOffset(): Float = this.zOffset
    }

    /** Client-only half (PORTING.md §2.3 rule C). */
    private object Client {
        fun getCustomModelsKey(): String? {
            val minecraft = Minecraft.getInstance()
            val serverData = minecraft.currentServer ?: return null

            var serverIp = serverData.ip
            val portIndex = serverIp.indexOf(":")
            if (portIndex != -1) {
                serverIp = serverIp.substring(0, portIndex)
            }

            return serverIp
        }

        /** Returns the user-facing error, or null when the geo model was baked and stored. */
        fun registerGeoModel(modelName: String, geoResource: ResourceLocation, geoFile: File, geoPath: String): String? {
            val rawGeoModel = try {
                ModelResources.loadGeoModel(geoFile)
            } catch (ioException2: IOException) {
                return String.format("The geo model for the custom model '%s' at '%s' appears to be corrupted. Try replacing it.", modelName, geoPath)
            }

            try {
                ModelResources.registerGeoModel(geoResource, rawGeoModel)
            } catch (exception2: Exception) {
                return String.format("The geo model for the custom model '%s' at '%s' appears to be corrupted. Try replacing it.", modelName, geoPath)
            }

            return null
        }
    }
}
