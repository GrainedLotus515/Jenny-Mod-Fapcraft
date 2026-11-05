package com.trolmastercard.sexmod.entity

import com.mojang.blaze3d.systems.RenderSystem
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.api.IPositionProvider
import com.trolmastercard.sexmod.api.ITargetProvider
import com.trolmastercard.sexmod.util.DynamicTrailRenderer
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * <b>Role.</b> CLIENT-only helper that renders the "cummy" cum-trail
 * overlays: holds a list of [DynamicTrailRenderer]s, updates them every
 * client tick and renders the bound texture in the world render pass. Girls
 * register trails from their animation sound listeners (e.g. the creampie
 * and masterbate keyframes in [GalathEntity.registerControllers] and
 * the threesome in [ManglelieEntity]); [spawnCummyTrails]
 * clears all trails of a girl (scene end).
 *
 * <b>Pitfalls.</b> The render list is static and never cleared except via
 * `spawnCummyTrails` — trails leak if a girl despawns without a cum
 * reset path.
 *
 * Port notes (CLIENT-ONLY class, registered from the client path):
 * `RenderWorldLastEvent` -> [RenderLevelStageEvent] at `AFTER_WEATHER`;
 * `ClientTickEvent` phase START -> [ClientTickEvent.Pre]. The 1.12 GL flag
 * snapshot/restore (lighting/alpha/depth) has no 1.21 counterpart — the
 * trail draw only toggles culling and restores it itself.
 */
open class CummyEntity {
    @SubscribeEvent
    open fun onRenderWorldLast(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return
        }

        if (trailRenderers.isEmpty() || mc.player == null) {
            return
        }

        try {
            RenderSystem.setShaderTexture(0, cummyTexture)
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(false)

            for (renderer in trailRenderers) {
                renderer.renderTrail(mc, partialTicks)
            }
        } finally {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        }
    }

    /** 1.12 `ClientTickEvent` with `phase != END`. */
    @SubscribeEvent
    open fun onClientTick(event: ClientTickEvent.Pre) {
        for (renderer in trailRenderers) {
            renderer.updateTrails()
        }
    }

    companion object {
        @JvmField
        val cummyTexture: ResourceLocation = Main.rl("textures/cummy.png")

        @JvmStatic
        val mc: Minecraft
            get() = Minecraft.getInstance()

        @JvmField
        var trailRenderers: MutableList<DynamicTrailRenderer> = ArrayList()

        @JvmStatic
        fun registerTrail(renderer: DynamicTrailRenderer) {
            trailRenderers.add(renderer)
        }

        @JvmStatic
        fun createTrail(
            maxSegmentsCount: Int,
            positionProvider: IPositionProvider,
            targetProvider: ITargetProvider,
            girl: BaseGirlEntity,
            randomnessRadius: Float,
            maxDistance: Float,
        ) {
            trailRenderers.add(DynamicTrailRenderer(maxSegmentsCount, positionProvider, targetProvider, girl, randomnessRadius, maxDistance))
        }

        /**
         * Removes every cum trail owned by the given girl (called on scene end so
         * trails do not persist into the next scene).
         */
        @JvmStatic
        fun spawnCummyTrails(girl: BaseGirlEntity) {
            val toRemove = ArrayList<DynamicTrailRenderer>()

            for (renderer in trailRenderers) {
                if (renderer.ownerEntity.getGirlId() == girl.getGirlId()) {
                    toRemove.add(renderer)
                }
            }

            trailRenderers.removeAll(toRemove)
        }
    }
}
