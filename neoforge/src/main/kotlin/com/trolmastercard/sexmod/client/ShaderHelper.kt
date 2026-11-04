package com.trolmastercard.sexmod.client

import com.trolmastercard.sexmod.Main
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.neoforged.neoforge.client.event.RegisterEntitySpectatorShadersEvent

/**
 * Owns the outline shader for girl entities (`shaders/post/outline.json`).
 *
 * Port note (PORTING.md §2.9): the 1.12 helper built a `ShaderGroup` by hand and
 * registered it with `ClientRegistry.registerEntityShader(BaseGirlEntity.class, ...)`.
 * In 1.21 the vanilla mechanism is the spectator entity shader, registered per entity type
 * in [RegisterEntitySpectatorShadersEvent]; vanilla loads and owns the post chain, so the
 * cached shader group / framebuffer fields are gone. [initOutlineShader] is the
 * registration hook; `ClientProxy` calls it from the event (S1 owns the listener).
 *
 * CLIENT-side only.
 */
object ShaderHelper {
    @JvmField
    val entityShader: ResourceLocation = Main.rl("shaders/post/outline.json")

    /**
     * Registers the outline post-processing shader for every given girl entity type.
     * Safe to call with an empty list.
     */
    @JvmStatic
    fun initOutlineShader(event: RegisterEntitySpectatorShadersEvent, girlTypes: Iterable<EntityType<*>>) {
        for (type in girlTypes) {
            event.register(type, entityShader)
        }
        Main.LOGGER.info("succ registered the outline shader :)")
    }
}
