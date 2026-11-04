package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the Slime NPC.
 */
class SlimeRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>, scaleFactor: Double) :
    GirlRenderer<BaseGirlEntity>(ctx, geoModel, scaleFactor) {
    override fun getBlacklistedBones(): HashSet<String> {
        // 1.12 added to (and so mutated) the shared BodyParts.CUSTOM_PART_BONES set - kept as-is
        val blacklisted = super.getBlacklistedBones()
        blacklisted.add("figure")
        return blacklisted
    }
}
