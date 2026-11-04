package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.SexSceneEntity
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.animation.AnimationState

/**
 * Geckolib model for [SexSceneEntity] (custom model parts). The model/
 * texture resolve per-part from the server whitelist
 * ([ServerWhitelistManager]); item-model parts use the built-in
 * `cross` geo/texture instead. Animations are not used
 * ([setCustomAnimations] is empty) — the pose comes from the bone
 * attachment logic in `SexSceneRenderer`.
 *
 * Port note: extends [GirlModelBase] (not plain `GeoModel`) so runtime custom geo models
 * resolve through the mod's own model map (PORTING.md §2.8).
 *
 * CLIENT-side render thread only.
 */
class SexSceneModel : GirlModelBase<SexSceneEntity>() {
    override fun getModelResource(sceneEntity: SexSceneEntity): ResourceLocation {
        return if (sceneEntity.isItemModel) Main.rl("geo/cross.geo.json") else ServerWhitelistManager.getModelResource(sceneEntity.getModelCode())
    }

    override fun getTextureResource(sceneEntity: SexSceneEntity): ResourceLocation {
        return if (sceneEntity.isItemModel) Main.rl("textures/cross.png") else ServerWhitelistManager.getModelTexture(sceneEntity.getModelCode())
    }

    override fun getAnimationResource(sceneEntity: SexSceneEntity): ResourceLocation = Main.rl("animations/slime/slime.animation.json")

    override fun setCustomAnimations(sceneEntity: SexSceneEntity, instanceId: Long, animationState: AnimationState<SexSceneEntity>) {
    }
}
