package com.trolmastercard.sexmod.proxy

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.ChestContainerGui
import com.trolmastercard.sexmod.client.gui.GenderSwapScreen
import com.trolmastercard.sexmod.client.gui.GirlInventoryContainerGui
import com.trolmastercard.sexmod.client.gui.GirlInventoryContainerGui2
import com.trolmastercard.sexmod.command.CommandFuta
import com.trolmastercard.sexmod.command.CommandSetModelCode
import com.trolmastercard.sexmod.command.CommandWhitelistServer
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.registry.ModMenus
import com.trolmastercard.sexmod.util.ForgeEventHandler
import com.trolmastercard.sexmod.util.ItemNbt
import com.trolmastercard.sexmod.util.RenderHandler
import com.trolmastercard.sexmod.util.SoundHandler
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.item.ItemProperties
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.client.GeoRenderProvider

/**
 * **Role.** Client-side half of the mod bootstrap ([CommonProxy] subclass;
 * CLIENT-ONLY class, created reflectively by `Main` on a physical client):
 * registers the render handler, keybindings (goblin interact / customization /
 * leave scene), client commands, menu screens, GeckoLib item renderers and the
 * item model properties, then the client event handlers.
 *
 * The 1.12 preload loop (rendering every NPC type into a `SexWorldClient`) and
 * the dragon-breath particle ID 625115 are dropped: GeckoLib 4 loads models on
 * resource reload, and `DragonBreathParticle` is added directly through the
 * particle engine. [IS_PRELOADING] therefore stays `false`.
 */
class ClientProxy : CommonProxy() {
    override fun construct(bus: IEventBus) {
        // GeckoLib 4 rejects unknown Molang variables at parse time; register them before the first reload
        com.trolmastercard.sexmod.util.MolangVars.registerAll()
        bus.addListener(RegisterKeyMappingsEvent::class.java) { e -> onRegisterKeyMappings(e) }
        bus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { e -> RenderHandler.register(e) }
        bus.addListener(RegisterMenuScreensEvent::class.java) { e -> onRegisterMenuScreens(e) }
        bus.addListener(RegisterClientExtensionsEvent::class.java) { e -> onRegisterClientExtensions(e) }
        bus.addListener(FMLClientSetupEvent::class.java) { e -> onClientSetup(e) }
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent::class.java) { e -> onRegisterClientCommands(e) }
    }

    override fun postInit(event: FMLLoadCompleteEvent) {
    }

    override fun preInitRegistries(event: FMLCommonSetupEvent) {
        super.preInitRegistries(event)
        // 1.12 RenderHandler.register() -> EntityRenderersEvent.RegisterRenderers listener (construct)
    }

    override fun initRegistries(event: FMLCommonSetupEvent) {
        // 1.12 key bindings -> RegisterKeyMappingsEvent (construct)
        try {
            Main.setConfigs()
        } catch (z: java.io.IOException) {
            Main.LOGGER.error(z)
        }
        SoundHandler.registerSounds()
        ForgeEventHandler.registerAll(true)
        // 1.12 preload loop dropped (see class doc)
        IS_PRELOADING = false
        GenderSwapScreen.instance = GenderSwapScreen()
        // 1.12 client commands -> RegisterClientCommandsEvent (construct)
        // 1.12 particle 625115 dropped (see class doc)
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        for (keyBinding in keyBindings) {
            event.register(keyBinding)
        }
    }

    private fun onRegisterMenuScreens(event: RegisterMenuScreensEvent) {
        event.register(ModMenus.GIRL_EQUIPMENT.get(), MenuScreens.ScreenConstructor { menu, inv, title -> GirlInventoryContainerGui2(menu, inv, title) })
        event.register(ModMenus.LUNA_EQUIPMENT.get(), MenuScreens.ScreenConstructor { menu, inv, title -> GirlInventoryContainerGui(menu, inv, title) })
        event.register(ModMenus.GIRL_CHEST.get(), MenuScreens.ScreenConstructor { menu, inv, title -> ChestContainerGui(menu, inv, title) })
    }

    /** GeckoLib items render through their `createGeoRenderer` provider (BEWLR, item model `builtin/entity`). */
    private fun onRegisterClientExtensions(event: RegisterClientExtensionsEvent) {
        for (holder in ModItems.ITEMS.entries) {
            val item: Item = holder.get()
            if (item is GeoItem) {
                event.registerItem(object : IClientItemExtensions {
                    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer =
                        GeoRenderProvider.of(item).geoItemRenderer
                            ?: Minecraft.getInstance().itemRenderer.blockEntityRenderer
                }, item)
            }
        }
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            // 1.12: npc_editor_wand_active was picked by item metadata 1
            ItemProperties.register(ModItems.EDITOR_WAND.get(), Main.rl("active")) { stack, _, _, _ ->
                if (ItemNbt.getMeta(stack) == 1) 1.0f else 0.0f
            }
            // 1.12 LunaRodItem overrode the "cast" property for this item only
            ItemProperties.register(ModItems.LUNA_ROD.get(), ResourceLocation.withDefaultNamespace("cast")) { _, _, entity, _ ->
                if (entity == null) {
                    0.0f
                } else if (entity !is LunaEntity) {
                    0.0f
                } else {
                    if (entity.getEntityData().get(LunaEntity.FISH_ON_LINE)) 1.0f else 0.0f
                }
            }
        }
    }

    private fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        CommandWhitelistServer.register(event.dispatcher)
        CommandSetModelCode.register(event.dispatcher)
        CommandFuta.register(event.dispatcher)
    }

    companion object {
        @JvmField
        var IS_PRELOADING = false

        /** 0 = G "Interact with your goblin", 1 = NUMPAD5 "open character customisation menu", 2 = RSHIFT "Leave sex scene". */
        @JvmField
        val keyBindings: Array<KeyMapping> = arrayOf(
            KeyMapping("Interact with your goblin", GLFW.GLFW_KEY_G, "Sex mod"),
            KeyMapping("open character customisation menu", GLFW.GLFW_KEY_KP_5, "Sex mod"),
            KeyMapping("Leave sex scene", GLFW.GLFW_KEY_RIGHT_SHIFT, "Sex mod"),
        )
    }
}
