package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the Bia NPC.
 */
class BiaRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>, scaleFactor: Double) :
    GirlRenderer<BaseGirlEntity>(ctx, geoModel, scaleFactor) {
    override fun getBlacklistedBones(): HashSet<String> {
        return hashSetOf("boobs", "booty", "vagina", "fuckhole", "leaf7", "leaf8")
    }
}
