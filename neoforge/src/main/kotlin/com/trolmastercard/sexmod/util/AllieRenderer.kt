package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.AllieEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRendererProvider
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the Allie NPC.
 */
class AllieRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>, scaleFactor: Double) :
    GirlRenderer<BaseGirlEntity>(ctx, geoModel, scaleFactor) {
    /**
     * Allie only renders while she is in an action (or as a preview): she grows out of the
     * lamp (`LAMP_SCALE` shrinks towards 1 by 0.01 per frame), scaled and lifted. The lamp
     * scale is also passed on as the alpha argument, as in 1.12.
     */
    override fun renderModel(model: BakedGeoModel, entity: BaseGirlEntity, partialTicks: Float, r: Float, g: Float, b: Float, a: Float) {
        val allie = entity as AllieEntity
        if (entity.getCurrentAction() != Action.NULL || entity.isLocallyRegistered()) {
            allie.LAMP_SCALE = if (allie.LAMP_SCALE == 1.0f) allie.LAMP_SCALE else allie.LAMP_SCALE - 0.01f
            val scale = allie.LAMP_SCALE
            val poseStack = this.renderCtx.poseStack
            poseStack.scale(scale, scale, scale)
            poseStack.translate(0.0f, if (scale == 1.0f) 0.0f else 3.0f - scale * 3.0f, 0.0f)
            super.renderModel(model, entity, partialTicks, r, g, b, scale)
        }
    }

    override fun renderNameTag(x: Double, y: Double, z: Double) {
        if (this.renderEntity.getCurrentAction() != Action.NULL) {
            if (!this.renderEntity.isLocallyRegistered()) {
                if (!this.renderEntity.getCurrentAction().hideNameTag) {
                    if (Minecraft.getInstance().getCameraEntity() != null) {
                        this.renderLivingLabel(this.renderEntity, this.renderEntity.getEffectiveDisplayName(), x, y + this.renderEntity.getScaleFactor(), z, 300)
                    }
                }
            }
        }
    }
}
