package com.trolmastercard.sexmod.client

import com.mojang.blaze3d.systems.RenderSystem
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.lwjgl.opengl.GL11

/**
 * CLIENT: gives the mod's "render world last" passes the depth state they had in 1.12.
 *
 * 1.12 `RenderWorldLastEvent` fired with depth writes on. Its 1.21 home,
 * [RenderLevelStageEvent.Stage.AFTER_WEATHER], sits inside vanilla's weather block, which
 * (outside Fabulous graphics) runs with `depthMask(false)`; the entity render types never turn
 * the mask back on. Models drawn there were therefore only sorted by quad distance, so flat
 * overlay quads lost to the surface right behind them (a first-person goblin showed one eye).
 *
 * [begin] runs before every other handler of the stage and [end] after the last one, so the
 * weather block gets its own state back.
 */
open class WorldLastDepthState {
    private var depthWritesWereOff = false

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    open fun begin(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return
        }

        this.depthWritesWereOff = !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        if (this.depthWritesWereOff) {
            RenderSystem.depthMask(true)
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    open fun end(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return
        }

        if (this.depthWritesWereOff) {
            RenderSystem.depthMask(false)
            this.depthWritesWereOff = false
        }
    }
}
