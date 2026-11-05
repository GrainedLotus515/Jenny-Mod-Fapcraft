package com.trolmastercard.sexmod.client.renderer

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.EllieEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib renderer for the Ellie NPC ([EllieEntity]); all rendering
 * logic lives in [GirlRenderer].
 */
class EllieRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>, shadowSize: Double) :
    GirlRenderer<EllieEntity>(ctx, model, shadowSize)
