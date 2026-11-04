package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.model.AllieNpcModel
import com.trolmastercard.sexmod.client.model.BeeNpcModel
import com.trolmastercard.sexmod.client.model.BiaNpcModel
import com.trolmastercard.sexmod.client.model.CatNpcModel
import com.trolmastercard.sexmod.client.model.EllieNpcModel
import com.trolmastercard.sexmod.client.model.GalathNpcModel
import com.trolmastercard.sexmod.client.model.GoblinNpcModel
import com.trolmastercard.sexmod.client.model.JennyNpcModel
import com.trolmastercard.sexmod.client.model.KoboldEggModel
import com.trolmastercard.sexmod.client.model.KoboldNpcModel
import com.trolmastercard.sexmod.client.model.ManglelieNpcModel
import com.trolmastercard.sexmod.client.model.SexSceneModel
import com.trolmastercard.sexmod.client.model.SlimeNpcModel
import com.trolmastercard.sexmod.client.renderer.BasicGirlRenderer
import com.trolmastercard.sexmod.client.renderer.BeeRenderer
import com.trolmastercard.sexmod.client.renderer.DragonRenderer
import com.trolmastercard.sexmod.client.renderer.EllieRenderer
import com.trolmastercard.sexmod.client.renderer.GalathRenderer
import com.trolmastercard.sexmod.client.renderer.GoblinRenderer
import com.trolmastercard.sexmod.client.renderer.JennyRenderer
import com.trolmastercard.sexmod.client.renderer.KoboldEggRenderer
import com.trolmastercard.sexmod.client.renderer.KoboldRenderer
import com.trolmastercard.sexmod.client.renderer.ManglelieRenderer
import com.trolmastercard.sexmod.client.renderer.SexEntityRenderer
import com.trolmastercard.sexmod.client.renderer.SexSceneRenderer
import com.trolmastercard.sexmod.client.renderer.WildSlimeRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.registry.ModEntities
import net.neoforged.neoforge.client.event.EntityRenderersEvent

/**
 * Registers all entity renderers (CLIENT). Called by `ClientProxy` from
 * [EntityRenderersEvent.RegisterRenderers]; same renderers, models and shadow offsets as 1.12.
 *
 * Port note: the 1.12 method also registered `GirlRenderer.SkinCacheInvalidator.INSTANCE`
 * on the event bus; `ForgeEventHandler.registerAll(true)` does that now (PORTING.md §2.10),
 * so it is not registered twice.
 */
object RenderHandler {
    @JvmStatic
    fun register(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntities.KOBOLD.get()) { ctx -> KoboldRenderer(ctx, KoboldNpcModel(), -0.4) }
        event.registerEntityRenderer(ModEntities.JENNY.get()) { ctx -> JennyRenderer(ctx, JennyNpcModel(), -0.15) }
        event.registerEntityRenderer(ModEntities.ELLIE.get()) { ctx -> EllieRenderer(ctx, EllieNpcModel(), 0.05) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.SLIME.get()) { ctx -> SlimeRenderer(ctx, SlimeNpcModel(), -0.2) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.BIA.get()) { ctx -> BiaRenderer(ctx, BiaNpcModel(), -0.4) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.ALLIE.get()) { ctx -> AllieRenderer(ctx, AllieNpcModel(), -0.4) }
        event.registerEntityRenderer(ModEntities.BEE.get()) { ctx -> BeeRenderer(ctx, BeeNpcModel(), -0.4) }
        event.registerEntityRenderer(ModEntities.FRIENDLY_SLIME.get()) { ctx -> WildSlimeRenderer(ctx) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.LUNA.get()) { ctx -> LunaRenderer(ctx, CatNpcModel(), -0.4) }
        event.registerEntityRenderer(ModEntities.GOBLIN.get()) { ctx -> GoblinRenderer(ctx, GoblinNpcModel(), -0.6) }
        event.registerEntityRenderer(ModEntities.GALATH.get()) { ctx -> GalathRenderer(ctx, GalathNpcModel(), -0.05) }
        event.registerEntityRenderer(ModEntities.KOBOLD_EGG.get()) { ctx -> KoboldEggRenderer(ctx, KoboldEggModel()) }
        event.registerEntityRenderer(ModEntities.MANGLELIE.get()) { ctx -> ManglelieRenderer(ctx, ManglelieNpcModel(), -0.05) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_BIA.get()) { ctx -> PlayerBiaRenderer(ctx, BiaNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_JENNY.get()) { ctx -> PlayerJennyRenderer(ctx, JennyNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_ELLIE.get()) { ctx -> PlayerEllieRenderer(ctx, EllieNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_SLIME.get()) { ctx -> PlayerSlimeRenderer(ctx, SlimeNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_ALLIE.get()) { ctx -> PlayerAllieRenderer(ctx, AllieNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_BEE.get()) { ctx -> PlayerBeeRenderer(ctx, BeeNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_LUNA.get()) { ctx -> PlayerLunaRenderer(ctx, CatNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_KOBOLD.get()) { ctx -> PlayerKoboldRenderer(ctx, KoboldNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_GOBLIN.get()) { ctx -> PlayerGoblinRenderer(ctx, GoblinNpcModel()) }
        event.registerEntityRenderer<BaseGirlEntity>(ModEntities.PLAYER_GALATH.get()) { ctx -> PlayerGalathRenderer(ctx, GalathNpcModel()) }
        event.registerEntityRenderer(ModEntities.LUNA_HOOK.get()) { ctx -> SexEntityRenderer(ctx) }
        event.registerEntityRenderer(ModEntities.CUSTOM_MODEL.get()) { ctx -> SexSceneRenderer(ctx, SexSceneModel()) }
        event.registerEntityRenderer(ModEntities.ENERGY_BALL.get()) { ctx -> DragonRenderer(ctx) }
        event.registerEntityRenderer(ModEntities.PYROCINICAL.get()) { ctx -> BasicGirlRenderer(ctx) }
    }
}
