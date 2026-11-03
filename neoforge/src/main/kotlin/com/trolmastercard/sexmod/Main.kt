package com.trolmastercard.sexmod

import com.trolmastercard.sexmod.client.model.GirlModel
import com.trolmastercard.sexmod.client.renderer.GirlRendererBase
import com.trolmastercard.sexmod.command.CommandLocateGoblinLair
import com.trolmastercard.sexmod.command.CommandReloadCustomModels
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.proxy.CommonProxy
import com.trolmastercard.sexmod.registry.ModBlocks
import com.trolmastercard.sexmod.registry.ModCreativeTabs
import com.trolmastercard.sexmod.registry.ModEffects
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.registry.ModMenus
import com.trolmastercard.sexmod.registry.ModSounds
import com.trolmastercard.sexmod.util.GirlRegistry
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.GirlWorldData
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import com.trolmastercard.sexmod.util.StructureMarkerRenderer
import com.trolmastercard.sexmod.worldgen.ConfigWorldGenHandler
import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.io.BufferedReader
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader

/**
 * **Role.** Mod entry point (`@Mod sexmod`). Wires the proxies through the
 * NeoForge lifecycle (common setup / load complete), registers every
 * DeferredRegister and the payload handlers, registers the server commands, and
 * resets all global state on world close: girl list, [KoboldManager], tribe UI
 * values, [GirlSavedData], worldgen config, [GirlRegistry],
 * [ServerWhitelistManager] and [GirlWorldData], plus client-side marker/bone-color
 * caches.
 *
 * **Pitfall.** [onWorldClosed] must clear everything that holds entity
 * references — stale statics across world reloads cause ghost girls and tribe
 * desyncs. [setConfigs] reads/writes `config/sexmod.json` (worldgen toggle,
 * skin loading, flying) and must keep its exact JSON layout, since the values
 * are parsed by character offset.
 */
@Mod(Main.MOD_ID)
object Main {
    const val MOD_ID = "sexmod"

    @JvmField
    val LOGGER: Logger = LogManager.getLogger("sexmod")

    /** Side proxy: `ClientProxy` on a physical client (created reflectively), otherwise [CommonProxy]. */
    @JvmStatic
    lateinit var proxy: CommonProxy

    @JvmStatic
    fun rl(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    init {
        printBanner()
        proxy = if (FMLEnvironment.dist.isClient) {
            Class.forName("com.trolmastercard.sexmod.proxy.ClientProxy").getDeclaredConstructor().newInstance() as CommonProxy
        } else {
            CommonProxy()
        }

        // 1. registries
        ModBlocks.register(MOD_BUS)
        ModItems.register(MOD_BUS)
        ModEntities.register(MOD_BUS)
        ModSounds.register(MOD_BUS)
        ModEffects.register(MOD_BUS)
        ModMenus.register(MOD_BUS)
        ModCreativeTabs.register(MOD_BUS)

        // 2. networking
        MOD_BUS.addListener(RegisterPayloadHandlersEvent::class.java) { e -> PacketHandler.register(e) }

        // 3./4. lifecycle (replaces the FML pre-init / init / post-init handlers)
        MOD_BUS.addListener(FMLCommonSetupEvent::class.java) { e ->
            proxy.preInitRegistries(e)
            proxy.initRegistries(e)
        }
        MOD_BUS.addListener(FMLLoadCompleteEvent::class.java) { e -> proxy.postInit(e) }

        // 5. game bus
        FORGE_BUS.addListener(RegisterCommandsEvent::class.java) { e -> onWorldStart(e) }
        FORGE_BUS.addListener(ServerStartingEvent::class.java) { e -> proxy.setUpCustomModelsOnServer(e.server) }
        FORGE_BUS.addListener(ServerStoppedEvent::class.java) { e -> onWorldClosed(e) }

        // 6. side-specific mod-bus listeners
        proxy.construct(MOD_BUS)
    }

    @JvmStatic
    private fun printBanner() {
        try {
            val stream = Main::class.java.getResourceAsStream("/banner.txt")
            BufferedReader(InputStreamReader(stream!!, "UTF-8")).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    LOGGER.info(line)
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            LOGGER.warn("Could not read banner.txt: {}", e.toString())
        }
    }

    @JvmStatic
    fun onWorldClosed(event: ServerStoppedEvent) {
        // 1.12 called getGirlEntityList().clear(); the list is rebuilt from the loaded worlds on every
        // call (a fresh copy), so that clear was a no-op and is omitted.
        KoboldManager.clearAll()
        KoboldEntity.tribeMembers.clear()
        GirlSavedData.clearAll()
        ConfigWorldGenHandler.getInstance().clear()
        GirlRegistry.clearAll()
        ServerWhitelistManager.isLoaded = false
        GirlWorldData.clearAll()
        if (FMLEnvironment.dist.isClient) {
            clientReset()
        }
    }

    /** 1.12 `FMLServerStartingEvent` command registration; now [RegisterCommandsEvent]. */
    @JvmStatic
    fun onWorldStart(event: RegisterCommandsEvent) {
        CommandLocateGoblinLair.register(event.dispatcher)
        CommandReloadCustomModels.register(event.dispatcher)
    }

    /** Client-only cache reset; only ever called when `FMLEnvironment.dist.isClient`. */
    @JvmStatic
    fun clientReset() {
        ClientResetHook.run()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setConfigs() {
        val configDir = FMLPaths.CONFIGDIR.get().toFile()
        configDir.mkdir()
        var configFile = FMLPaths.CONFIGDIR.get().resolve("sexmod.json").toFile()
        if (!configFile.exists()) {
            configFile.createNewFile()
            val writer = FileWriter(configFile)
            writer.write("{\"shouldGenBuildings\":true,\"shouldLoadOtherSkins\":false,\"allowFlying\":true}")
            writer.close()
        }

        val jsonBuilder = StringBuilder()
        val reader = BufferedReader(FileReader(configFile))
        try {
            var line = reader.readLine()
            while (line != null) {
                jsonBuilder.append(line)
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }

        val json = jsonBuilder.toString()
        if (!json.contains("shouldGenBuildings")) {
            configFile.delete()
            configFile = FMLPaths.CONFIGDIR.get().resolve("sexmod.json").toFile()
            configFile.createNewFile()
            val writer2 = FileWriter(configFile)
            writer2.write("{\"shouldGenBuildings\":true,\"shouldLoadOtherSkins\":false,\"allowFlying\":true}")
            writer2.close()
            ConfigWorldGenHandler.GENERATION_ENABLED = true
            setModelCacheEnabled(false)
            AbstractPlayerGirlEntity.flightGrantEnabled = true
        } else {
            val genIdx = json.indexOf("shouldGenBuildings")
            val skinIdx = json.indexOf("shouldLoadOtherSkins")
            val flyIdx = json.indexOf("allowFlying")
            ConfigWorldGenHandler.GENERATION_ENABLED = 't' == json[genIdx + 20]
            setModelCacheEnabled('t' == json[skinIdx + 22])
            AbstractPlayerGirlEntity.flightGrantEnabled = 't' == json[flyIdx + 13]
        }
    }

    /**
     * `GirlModel.enableModelCache` lives on a client-only class; the dedicated
     * server has no use for it, so the write only happens on a physical client
     * (§2.3 Rule B).
     */
    private fun setModelCacheEnabled(value: Boolean) {
        if (FMLEnvironment.dist.isClient) {
            ClientResetHook.setModelCache(value)
        }
    }

    /** Client-only half of [Main] (§2.3 Rule C). Loaded only on a physical client. */
    private object ClientResetHook {
        fun run() {
            StructureMarkerRenderer.clearMarkers()
            GirlRendererBase.clearBoneColors()
        }

        fun setModelCache(value: Boolean) {
            GirlModel.enableModelCache = value
        }
    }
}
