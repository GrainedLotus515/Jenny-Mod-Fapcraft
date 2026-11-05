package com.trolmastercard.sexmod.client.renderer

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BeeEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib renderer for the bee girl ([BeeEntity]); all rendering logic
 * lives in [GirlRenderer]. Registered per entity type in [com.trolmastercard.sexmod.util.RenderHandler]
 * with its [com.trolmastercard.sexmod.client.model.BeeNpcModel] and shadow offset.
 */
class BeeRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>, shadowSize: Double) :
    GirlRenderer<BeeEntity>(ctx, model, shadowSize)
