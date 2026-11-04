package com.trolmastercard.sexmod.client.renderer

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.JennyEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib renderer for the Jenny NPC ([JennyEntity]); all rendering
 * logic lives in [GirlRenderer].
 */
class JennyRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>, shadowSize: Double) :
    GirlRenderer<JennyEntity>(ctx, model, shadowSize)
