package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.PositionData
import com.trolmastercard.sexmod.client.GirlCameraHelper
import com.trolmastercard.sexmod.client.SexSceneKeyHandler
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.ClothingScreen
import com.trolmastercard.sexmod.client.gui.EscapeMinigameHud
import com.trolmastercard.sexmod.client.gui.GalathFlightHud
import com.trolmastercard.sexmod.client.gui.GenderSwapScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.entity.CummyEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.GoblinEventHandler
import com.trolmastercard.sexmod.entity.GoblinPlayerEntity
import com.trolmastercard.sexmod.entity.KoboldEggProjectileEntity
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.entity.ManglelieEntity
import com.trolmastercard.sexmod.entity.ai.GirlFollowGoal
import com.trolmastercard.sexmod.item.AlliesLampItem
import com.trolmastercard.sexmod.item.DragonStaffItem
import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.item.KoboldEggItem
import com.trolmastercard.sexmod.item.LunaRodItem
import com.trolmastercard.sexmod.item.NpcEditorWandItem
import com.trolmastercard.sexmod.potion.HornyPotion
import com.trolmastercard.sexmod.reskin.ReskinManager
import com.trolmastercard.sexmod.worldgen.ConfigWorldGenHandler
import net.neoforged.neoforge.common.NeoForge
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * Central event registration hub (entity spawn, ticks, interactions).
 * `registerAll(true/false)` sets up the game-bus subscriptions; the mod's
 * handlers (HandlePlayerMovement, GirlCameraHelper, HornyMeterHud,
 * GirlSavedData, PlayerIds, ...) are registered here.
 *
 * 1.21 notes (PORTING.md §2.10):
 * - "`.Events`" objects replace the 1.12 pattern of registering an item or
 *   effect instance; `.ClientEvents` objects are the client halves split out of
 *   common handlers (§2.3 Rule E) and are registered from the client path only.
 * - `NeoForge.EVENT_BUS.register` throws for objects without `@SubscribeEvent`
 *   methods, so `WinchesterItem` (no handlers) and the fire block (its only
 *   1.12 handler was block registration) are not registered.
 * - [LootTableHandler.AlliesLampLoot] carries the 1.12 `AlliesLampItem.onLootTableLoad`.
 * - `GoblinFirstPersonRenderer` moved from the common list to the client list.
 */
object ForgeEventHandler {
    @JvmStatic
    fun registerAll(showDebug: Boolean) {
        if (showDebug) {
            // client-only reskin manager (lazy pack import + reload persistence)
            Client.registerReskinManager()
        }
        val bus = NeoForge.EVENT_BUS
        bus.register(GirlCombatProtection())
        bus.register(LivingDeathHandler())
        bus.register(PlayerIds())
        bus.register(HornyPotion.Events)
        bus.register(DamageCalculation())
        bus.register(KoboldEggProjectileEntity.EnderTeleportHandler())
        bus.register(GirlFollowGoal.DownedGirlEvents())
        bus.register(AlliesLampItem.Events)
        bus.register(DragonStaffItem.Events)
        bus.register(NpcEditorWandItem.Events)
        bus.register(LunaRodItem.Events)
        bus.register(PlayerGirlEvents())
        bus.register(LunaEntity.CreeperAvoidanceHandler())
        bus.register(GirlBedInteraction())
        bus.register(KoboldEntity.KoboldTribeEvents())
        bus.register(DragonStaffItem.RightClickHandler())
        bus.register(KoboldManager.TribeWorldSavedData("tribes"))
        bus.register(KoboldEggItem.Events)
        bus.register(GoblinEventHandler())
        bus.register(GoblinPlayerEntity.GoblinCarryEvents())
        bus.register(AlliesLampItem.InteractionHandler())
        bus.register(DebugMode())
        bus.register(GalathEntity.GalathLifecycleEvents())
        bus.register(GirlSavedData())
        bus.register(GalathCoinItem.Events)
        bus.register(BeeWorldData())
        bus.register(AllieWorldData())
        bus.register(GirlWorldData())
        bus.register(ConfigWorldGenHandler.getInstance())
        bus.register(ManglelieEntity.ArrowImpactHandler())
        bus.register(NameTagInteractHandler())
        bus.register(LootTableHandler.AlliesLampLoot)
        if (showDebug) {
            registerDebugWindow()
        }
    }

    /** Client-only handlers; only called with `showDebug == true` (the client proxy). */
    @JvmStatic
    fun registerDebugWindow() {
        Client.registerClientHandlers()
    }

    @JvmStatic
    fun shouldShowDebugWindow(): Boolean {
        val dontAskAgainFile = File("sexmod/dontAskAgain")
        dontAskAgainFile.parentFile.mkdirs()
        return !dontAskAgainFile.exists()
    }

    /** Client-only half (§2.3 Rule C): mentions client-only handler types. */
    private object Client {
        fun registerReskinManager() {
            NeoForge.EVENT_BUS.register(ReskinManager.INSTANCE)
        }

        fun registerClientHandlers() {
            val bus = NeoForge.EVENT_BUS
            if (shouldShowDebugWindow() && !GraphicsEnvironment.isHeadless()) {
                bus.register(DebugWindow())
            } else {
                DebugWindow2.isVisible = false
            }

            bus.register(HornyMeterHud())
            bus.register(BeeScreen())
            bus.register(HandlePlayerMovement())
            bus.register(PositionData())
            bus.register(GuiOpenHandler())
            bus.register(InHandMapRenderer())
            bus.register(GirlCameraHelper())
            bus.register(SexSceneKeyHandler())
            bus.register(GenderSwapScreen())
            bus.register(PlayerAllieRenderer.ClientTickHandler())
            bus.register(StructureMarkerRenderer())
            bus.register(DeprecatedCheckForUpdates())
            bus.register(ClothingScreen.KeyAndScrollHandler())
            bus.register(ServerWhitelistManager.ChatHandler())
            bus.register(EscapeMinigameHud())
            bus.register(CummyEntity())
            bus.register(GalathFlightHud())
            bus.register(GoblinFirstPersonRenderer())
            bus.register(GirlRenderer.SkinCacheInvalidator.INSTANCE)

            // client halves split out of common handlers (Rule E)
            bus.register(AlliesLampItem.ClientEvents)
            bus.register(DebugMode.ClientEvents)
            bus.register(GoblinEventHandler.ClientEvents)
            bus.register(GalathEntity.GalathLifecycleEvents.ClientEvents)
            bus.register(GoblinPlayerEntity.GoblinCarryEvents.ClientEvents)
            bus.register(KoboldEntity.KoboldTribeEvents.ClientEvents)
            bus.register(PlayerGirlEvents.ClientEvents)
        }
    }
}
