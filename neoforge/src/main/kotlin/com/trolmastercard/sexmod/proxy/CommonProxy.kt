package com.trolmastercard.sexmod.proxy

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.ForgeEventHandler
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import com.trolmastercard.sexmod.util.SoundHandler
import net.minecraft.server.MinecraftServer
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent

/**
 * **Role.** Common (server-capable) half of the mod bootstrap. In 1.12 it
 * registered the world generator, entities, items, sounds, the GUI handler and
 * `PacketHandler`; in 1.21 those are DeferredRegisters / mod-bus listeners set
 * up by `Main`, `ConfigWorldGenHandler` hooks chunk events itself and the GUI
 * handler is replaced by menu types. What remains: reading the config and
 * registering the game-bus handlers. On a dedicated server it also loads the
 * custom-model registry (`ServerWhitelistManager.loadCustomModels(false)`) so
 * model files can be served to clients.
 */
open class CommonProxy {
    /** Side-specific mod-bus listeners; called at the end of `Main`'s constructor. */
    open fun construct(bus: IEventBus) {
    }

    open fun preInitRegistries(event: FMLCommonSetupEvent) {
        // 1.12: world generator, entity and item registration. All of it moved to
        // DeferredRegisters (registry/*) and ConfigWorldGenHandler's own chunk hooks.
    }

    open fun initRegistries(event: FMLCommonSetupEvent) {
        try {
            Main.setConfigs()
        } catch (e: java.io.IOException) {
            Main.LOGGER.error(e)
        }
        SoundHandler.registerSounds()
        ForgeEventHandler.registerAll(false)
    }

    open fun postInit(event: FMLLoadCompleteEvent) {
    }

    /** Called from `ServerStartingEvent` (1.12: post-init). Dedicated servers only. */
    open fun setUpCustomModelsOnServer(server: MinecraftServer) {
        if (server.isDedicatedServer) {
            ServerWhitelistManager.loadCustomModels(false)
        }
    }

    companion object {
        @JvmField
        val PROXY: CommonProxy = CommonProxy()
    }
}
