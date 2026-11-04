package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.model.GirlModel
import com.trolmastercard.sexmod.client.renderer.api.IGirlRenderer
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BodyParts
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import java.awt.Color
import java.util.LinkedHashMap
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.horse.Horse
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube
import software.bernie.geckolib.constant.DataTickets
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.model.data.EntityModelData
import software.bernie.geckolib.renderer.GeoEntityRenderer
import software.bernie.geckolib.util.RenderUtil

/**
 * Core geckolib renderer for every girl entity ([BaseGirlEntity] +
 * GeoEntity): Jenny, Ellie, Bee, Manglelie, and via
 * [GirlRendererBase] the NPC-only girls. Owns skin textures, render
 * gating, the custom-bone pass (held items, payment overlay), camera-bone
 * world positions, anchored-scene positioning and armor tinting.
 *
 * **Anchored rendering.** [getBoneWorldPos]
 * is where the anchored-scene pose lives: an anchored girl is rendered at
 * `getTargetPosition()` (relative to the local player) with her yaw
 * pinned to `getYawRotation()` — this is what places the girl correctly
 * during sex scenes. Do NOT remove the anchored offset or yaw-pinning, and
 * keep the `lerpVec3dDouble` partial-tick interpolation (PROGRESS lerp,
 * correct for render code).
 *
 * **Render gating.** [canRenderPlayer] hides the girl when a solid
 * block occludes every corner of her bounding box from the player's eye
 * (first person) so she cannot be seen through walls; [renderModel]
 * additionally skips locally registered / non-renderable entities.
 *
 * **Custom bones.** `weapon` draws the held item
 * ([renderHeldItem], bow-pull and sword-stab poses), `itemRenderer`
 * draws the payment item while the action is `PAYMENT`
 * ([renderTradeOverlay]); `ballL/ballR/cock` are forced opaque;
 * `Head2` is hidden in first person; `armor*` bones get armor
 * material tinting ([calculateBoneArmorColor]).
 *
 * **Scene hooks.** After every render [applyCameraBone] publishes
 * world positions for [GirlModel.CAMERA_PLACEMENTS] + tracked bones
 * (read by camera code), and
 * [SexSceneRenderer.renderSexSceneEffects] draws scene parts.
 *
 * CLIENT-side render thread only. Skin textures are fetched once per UUID via
 * `SkinFetcher` and kept in a bounded LRU ([SKIN_TEXTURE_CACHE]); a texture is
 * re-fetched after eviction or after [invalidate].
 * The [SexWorldClient] preload world skips the custom pass entirely.
 *
 * ## 1.21 pipeline (PORTING.md §2.9)
 *
 * 1. [render]: Java `doRender`/`doRenderEntity`/`getBoneWorldPos`. Computes the 1.12
 *    render position (relative to the local player), converts the difference to the
 *    vanilla entity origin into a pose translation, draws name tag and leash, then runs
 *    GeckoLib's `defaultRender`.
 * 2. [actuallyRender]: Java's duplicated GeckoLib `doRender` body (rotations, limb swing,
 *    sitting clamps, `isMoving` = `|limbSwingAmount| >= 0.15`, `setLivingAnimations`
 *    → `handleAnimations`, the 0.01 lift) followed by [renderModel].
 * 3. [renderModel] → [renderModelBuffer] → mod [renderRecursively] (float colour
 *    recursion, Java per-bone logic) → [renderCubeGeometry] (vertex emission).
 * 4. [renderFinal]: [applyCameraBone], scene effects, overlay colour.
 *
 * Hooks that took a `BufferBuilder`/`Tessellator` in Java keep their names and the
 * remaining parameters; they read [renderCtx] instead ([GirlRenderContext]).
 * Pose-matrix hooks (`GlStateManager.rotate/translate`) act on `renderCtx.poseStack`.
 */
abstract class GirlRenderer<T : BaseGirlEntity>(
    ctx: EntityRendererProvider.Context,
    model: GeoModel<out BaseGirlEntity>,
    shadowOffset: Double,
) : GeoEntityRenderer<T>(ctx, castModel(model)), IGirlRenderer {
    @JvmField
    var shadowOffset: Double = shadowOffset

    /** The girl currently being rendered (Java `renderEntity`). */
    lateinit var renderEntity: T

    @JvmField
    var BASE_SKIN_COLOR: Color = Color(245, 199, 165)

    @JvmField
    var BLUSH_COLOR: Color = Color(245, 157, 169)

    @JvmField
    var activeCustomPartBones: HashSet<String> = HashSet()

    @JvmField
    var bowPullProgress: Float = 0.0f

    @JvmField
    var currentRenderingBone: GeoBone? = null

    /** Per-frame render state; replaces the 1.12 `BufferBuilder` parameters (see [GirlRenderContext]). */
    @JvmField
    val renderCtx: GirlRenderContext = GirlRenderContext(this)

    /**
     * The 1.12 render position of the girl relative to the local player (Java `x, y, z` of
     * `doRender`), captured before [getBoneWorldPos] moved it. Used to convert Java-frame
     * coordinates passed to [renderLivingLabel].
     */
    @JvmField
    var javaRenderBase: Vec3 = Vec3.ZERO

    /** Offset from the vanilla entity origin to the position [getBoneWorldPos] chose. */
    @JvmField
    var renderDelta: Vec3 = Vec3.ZERO

    init {
        this.shadowRadius = 0.2f
    }

    // ---------------------------------------------------------------------------------
    // Render types
    // ---------------------------------------------------------------------------------

    /**
     * The render type used for every girl texture pass. 1.12 drew with blending on
     * (`SRC_ALPHA, ONE_MINUS_SRC_ALPHA`), so the translucent entity type is used.
     */
    open fun getGirlRenderType(texture: ResourceLocation): RenderType = RenderType.entityTranslucent(texture)

    /** 1.12 drew girls regardless of invisibility; the girl render type is always used. */
    override fun getRenderType(animatable: T, texture: ResourceLocation, bufferSource: MultiBufferSource?, partialTick: Float): RenderType? =
        this.getGirlRenderType(texture)

    /** 1.12 `IGeoRenderer.getRenderColor`: plain white (no invisibility fade). */
    override fun getRenderColor(animatable: T, partialTick: Float, packedLight: Int): software.bernie.geckolib.util.Color =
        software.bernie.geckolib.util.Color.WHITE

    // ---------------------------------------------------------------------------------
    // Skins
    // ---------------------------------------------------------------------------------

    /**
     * Skin texture for the girl: the interaction player's cached skin, or a
     * freshly tinted one; in the preload world / without an interaction player
     * the local player's profile id is used. Cache lives in the static map.
     */
    open fun getSkinTexture(girl: T): ResourceLocation {
        val cachedTexture: ResourceLocation?
        val interactionUuid = girl.getInteractionPlayerUUID()
        if (!SexWorldClient.isPreloadWorld(girl.level()) && interactionUuid != null) {
            cachedTexture = SKIN_TEXTURE_CACHE[interactionUuid]
            if (cachedTexture == null) {
                return this.getTintedSkinTexture(interactionUuid, girl.level())
            }
        } else {
            cachedTexture = SKIN_TEXTURE_CACHE[ClientHooks.sessionProfileId()]
            if (cachedTexture == null) {
                return this.getTintedSkinTexture(ClientHooks.sessionProfileId(), girl.level())
            }
        }

        return cachedTexture
    }

    /**
     * Hash accessor for the shared tinted-skin LRU; the fetch/tint/evict work
     * lives in [SkinTextureCache].
     */
    open fun getTintedSkinTexture(uuid: UUID, world: Level): ResourceLocation {
        return SkinTextureCache.tint(uuid, this.BASE_SKIN_COLOR, this.BLUSH_COLOR)
    }

    /**
     * Clears the tinted-skin cache when the client leaves or joins a server, so
     * a skin changed between sessions is fetched again. Registered by
     * `ForgeEventHandler.registerAll(true)`.
     */
    class SkinCacheInvalidator {
        @SubscribeEvent
        fun onClientConnected(event: ClientPlayerNetworkEvent.LoggingIn) {
            invalidateAll()
        }

        @SubscribeEvent
        fun onClientDisconnected(event: ClientPlayerNetworkEvent.LoggingOut) {
            invalidateAll()
        }

        companion object {
            @JvmField
            val INSTANCE: SkinCacheInvalidator = SkinCacheInvalidator()
        }
    }

    open fun renderLeftEye() {
    }

    open fun renderRightEye() {
    }

    open fun rayTraceBoneDistance(world: Level, eyePos: Vec3, yaw: Float, pitch: Float): Float {
        return RenderOcclusion.rayTraceBoneDistance(world, eyePos, yaw, pitch)
    }

    /**
     * First-person occlusion gate: the girl is only rendered when at least one
     * corner of her bounding box has an unobstructed (or translucent/non-solid)
     * ray from the player's eye. Player-girls always render. In third person
     * the camera position is moved behind a solid block when needed.
     *
     * @return `true` if the girl may be drawn
     */
    open fun canRenderPlayer(girl: T, player: Player): Boolean {
        return RenderOcclusion.canRender(girl, player, ClientHooks.thirdPersonView())
    }

    /**
     * The set of bones currently replaced by custom parts: from the
     * `ClothingScreen` preview (locally registered) or the girl's own
     * custom-part set; disabled models' bones are added regardless of
     * `isNudeOutfit`. Empty while `ClientProxy.IS_PRELOADING`.
     */
    open fun getActiveBones(isPreview: Boolean, isNudeOutfit: Boolean): HashSet<String> {
        return CustomPartBones.resolve(this.renderEntity, isPreview, isNudeOutfit)
    }

    // ---------------------------------------------------------------------------------
    // Entry point (Java doRender / doRenderEntity / getBoneWorldPos)
    // ---------------------------------------------------------------------------------

    /**
     * The full girl render pipeline (CLIENT-side): resolves the bone world
     * position (anchored/target-position logic, see [getBoneWorldPos]),
     * draws the leash, then runs the geckolib pipeline (animation state with
     * head yaw/pitch, limb swing and the sitting pose with vanilla riding clamps
     * in [actuallyRender]), renders the model, layers, camera-bone
     * positions, scene effects ([SexSceneRenderer.renderSexSceneEffects])
     * and the optional girl overlay color ([renderFinal]).
     *
     * **Ordering matters:** model -> layers -> camera bone -> scene effects.
     * The camera bone pass must run after the model is drawn so
     * `GirlModel.CAMERA_PLACEMENTS` reflect this frame's pose.
     */
    override fun render(entity: T, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.doRenderEntity(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    /**
     * Java `doRenderEntity`. Subclasses that gate or wrap the whole girl render
     * (player-girls, Galath, Manglelie) override this and call `super`.
     */
    open fun doRenderEntity(entity: T, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.renderEntity = entity
        this.renderCtx.poseStack = poseStack
        this.renderCtx.bufferSource = bufferSource
        this.renderCtx.packedLight = packedLight
        this.renderCtx.partialTick = partialTick
        this.renderCtx.isReRender = false

        // Java x, y, z: the entity's lerped position relative to the lerped local player
        val entityLerp = lerpedPosition(entity, partialTick)
        val player = Minecraft.getInstance().player
        val playerLerp = if (player == null) entityLerp else lerpedPosition(player, partialTick)
        val x = entityLerp.x - playerLerp.x
        val y = entityLerp.y - playerLerp.y
        val z = entityLerp.z - playerLerp.z
        this.javaRenderBase = Vec3(x, y, z)

        var boneWorldPos = this.getBoneWorldPos(entity, partialTick, x, y, z)
        boneWorldPos = this.getBoneWorldPos(entity, partialTick, boneWorldPos)
        this.renderDelta = boneWorldPos.subtract(this.javaRenderBase)
        this.onBoneRenderStart(entity)

        poseStack.pushPose()
        try {
            poseStack.translate(this.renderDelta.x, this.renderDelta.y, this.renderDelta.z)
            if (entity.isLeashed) {
                this.renderLeash(entity, 0.0, this.shadowOffset, 0.0, partialTick)
            }

            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
        } finally {
            poseStack.popPose()
        }
    }

    open fun buildModelCode(path: String): String = ModelCodeReader.read(path)

    /**
     * Draws the girl's name label (Java `renderNameTag(x, y, z)`); coordinates are in the
     * 1.12 frame (relative to the local player).
     */
    open fun renderNameTag(x: Double, y: Double, z: Double) {
        if (!this.renderEntity.isLocallyRegistered()) {
            if (!this.renderEntity.getCurrentAction().hideNameTag) {
                if (Minecraft.getInstance().getCameraEntity() != null) {
                    this.renderLivingLabel(this.renderEntity, this.renderEntity.getEffectiveDisplayName(), x, y + this.renderEntity.getScaleFactor(), z, 300)
                }
            }
        }
    }

    /**
     * Java `renderLivingLabel(entity, text, x, y, z, maxDistance)`. The coordinates are in the
     * 1.12 frame (relative to the local player); vanilla adds the name-tag attachment height
     * (`height + 0.5`, as 1.12 did).
     *
     * Port note: vanilla 1.21 culls name tags beyond 64 blocks; `maxDistance` is ignored.
     */
    open fun renderLivingLabel(entity: T, text: String, x: Double, y: Double, z: Double, maxDistance: Int) {
        val poseStack = this.renderCtx.poseStack
        val bufferSource = this.renderCtx.bufferSource ?: return
        poseStack.pushPose()
        try {
            poseStack.translate(x - this.javaRenderBase.x, y - this.javaRenderBase.y, z - this.javaRenderBase.z)
            this.renderNameTag(entity, Component.literal(text), poseStack, bufferSource, this.renderCtx.packedLight, this.renderCtx.partialTick)
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Render offset while the girl's master rides a saddled horse: positions
     * the girl behind the horse's head (relative to the local player) and
     * copies the ridden entity's yaw onto her.
     */
    open fun getRidingOffset(player: Player, partialTicks: Float): Vec3 {
        val ridingEntity = player.vehicle as LivingEntity
        val localPlayer = Minecraft.getInstance().player!! // java-npe: only called while rendering in a world
        val lookVec = ridingEntity.lookAngle
        val playerPos = RotationHelper.lerpVec3dDouble(Vec3(player.xOld, player.yOld, player.zOld), player.position(), partialTicks.toDouble())
        var localPlayerPos = RotationHelper.lerpVec3dDouble(Vec3(localPlayer.xOld, localPlayer.yOld, localPlayer.zOld), localPlayer.position(), partialTicks.toDouble())
        localPlayerPos = playerPos.subtract(localPlayerPos)
        this.renderEntity.yBodyRot = ridingEntity.yBodyRot
        return Vec3(localPlayerPos.x + lookVec.x * -0.5, localPlayerPos.y + 0.15f, localPlayerPos.z + lookVec.z * -0.5)
    }

    /**
     * Hook for subclasses to override the final bone world position.
     */
    open fun getBoneWorldPos(entity: T, partialTicks: Float, pos: Vec3): Vec3 {
        return pos
    }

    /**
     * Computes the render position for the girl (CLIENT-side).
     *
     * **Anchored girls render at `getTargetPosition()`,** relative to
     * the lerped local player position, with every yaw field pinned to
     * `getYawRotation()` — this is the scene pose; player-girls owned by
     * another player only do this in third person. Unanchored girls render at
     * their world position. Also: name-tag rendering, horse-riding offset, and
     * a no-op for the [SexWorldClient] preload world.
     *
     * **Pitfall:** the partial-tick lerp here uses
     * [RotationHelper.lerpVec3dDouble] (PROGRESS) — correct for render
     * interpolation; do not switch to the INT step variant.
     */
    open fun getBoneWorldPos(entity: T, partialTicks: Float, x: Double, y: Double, z: Double): Vec3 {
        var pos = Vec3(x, y, z)
        if (SexWorldClient.isPreloadWorld(entity.level())) {
            return pos
        }

        if (entity.shouldRenderNameTag() && (entity !is AbstractPlayerGirlEntity || ClientHooks.thirdPersonView() != 0)) {
            this.renderNameTag(x, y, z)
        }

        val master = entity.getMasterPlayer()
        if (master != null && master.isPassenger) {
            val mount = master.vehicle
            if (mount is Horse && mount.isSaddled) {
                return this.getRidingOffset(master, partialTicks)
            }
        }

        if (!entity.isAnchored()) {
            return pos
        }

        val mcPlayer = Minecraft.getInstance().player
        if (mcPlayer != null &&
            (entity !is AbstractPlayerGirlEntity || !entity.hasOwnerUUID() || ClientHooks.thirdPersonView() == 0)
        ) {
            val lerpedPlayerPos = RotationHelper.lerpVec3dDouble(
                Vec3(mcPlayer.xOld, mcPlayer.yOld, mcPlayer.zOld),
                mcPlayer.position(),
                partialTicks.toDouble(),
            )
            pos = entity.getTargetPosition().subtract(lerpedPlayerPos)
        }

        val yaw = entity.getYawRotation()
        entity.setYRot(yaw)
        entity.yBodyRotO = yaw
        entity.yBodyRot = yaw
        entity.yHeadRotO = yaw
        entity.yHeadRot = yaw
        return pos
    }

    open fun onBoneRenderStart(entity: T) {
    }

    // ---------------------------------------------------------------------------------
    // GeckoLib pipeline
    // ---------------------------------------------------------------------------------

    /**
     * Java `doRenderEntity` model block: rotations, the animation event (head yaw/pitch,
     * limb swing, sitting pose with vanilla riding clamps), `setLivingAnimations`, the
     * 0.01 lift, then [renderModel].
     *
     * The GL3 `isMoving` rule (`|limbSwingAmount| >= 0.15`) is kept instead of GeckoLib 4's
     * velocity threshold.
     */
    override fun actuallyRender(
        poseStack: PoseStack,
        animatable: T,
        model: BakedGeoModel,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer?,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        poseStack.pushPose()
        try {
            val isSitting = animatable.isPassenger && animatable.vehicle != null && animatable.vehicle!!.shouldRiderSit()
            var renderYawOffset = Mth.rotLerp(partialTick, animatable.yBodyRotO, animatable.yBodyRot)
            val rotationYawHead = Mth.rotLerp(partialTick, animatable.yHeadRotO, animatable.yHeadRot)
            var headYawDelta = rotationYawHead - renderYawOffset
            val vehicle = animatable.vehicle
            if (isSitting && vehicle is LivingEntity) {
                renderYawOffset = Mth.rotLerp(partialTick, vehicle.yBodyRotO, vehicle.yBodyRot)
                headYawDelta = rotationYawHead - renderYawOffset
                var clampedHeadYawDelta = Mth.wrapDegrees(headYawDelta)
                if (clampedHeadYawDelta < -85.0f) {
                    clampedHeadYawDelta = -85.0f
                }

                if (clampedHeadYawDelta >= 85.0f) {
                    clampedHeadYawDelta = 85.0f
                }

                renderYawOffset = rotationYawHead - clampedHeadYawDelta
                if (clampedHeadYawDelta * clampedHeadYawDelta > 2500.0f) {
                    renderYawOffset += clampedHeadYawDelta * 0.2f
                }

                headYawDelta = rotationYawHead - renderYawOffset
            }

            val pitch = Mth.lerp(partialTick, animatable.xRotO, animatable.xRot)
            val rotationFloat = animatable.tickCount.toFloat() + partialTick
            val nativeScale = animatable.scale
            poseStack.scale(nativeScale, nativeScale, nativeScale)
            this.applyRotations(animatable, poseStack, rotationFloat, renderYawOffset, partialTick, nativeScale)
            var limbSwingAmount = 0.0f
            var limbSwing = 0.0f
            if (!isSitting && animatable.isAlive) {
                limbSwingAmount = animatable.walkAnimation.speed(partialTick)
                limbSwing = animatable.walkAnimation.position(partialTick)
                if (animatable.isBaby) {
                    limbSwing *= 3.0f
                }

                if (limbSwingAmount > 1.0f) {
                    limbSwingAmount = 1.0f
                }
            }

            if (!isReRender) {
                val animationState = AnimationState(
                    animatable,
                    limbSwing,
                    limbSwingAmount,
                    partialTick,
                    !(limbSwingAmount > -0.15f) || !(limbSwingAmount < 0.15f),
                )
                val instanceId = this.getInstanceId(animatable)
                val currentModel = this.geoModel
                animationState.setData(DataTickets.TICK, animatable.getTick(animatable))
                animationState.setData(DataTickets.ENTITY, animatable)
                animationState.setData(DataTickets.ENTITY_MODEL_DATA, EntityModelData(isSitting, animatable.isBaby, -headYawDelta, -pitch))
                currentModel.addAdditionalStateData(animatable, instanceId) { ticket, value -> animationState.setData(ticket, value) }
                currentModel.handleAnimations(animatable, instanceId, animationState, partialTick)
            }

            poseStack.translate(0.0f, 0.01f, 0.0f)
            this.modelRenderTranslations = Matrix4f(poseStack.last().pose())

            this.renderCtx.poseStack = poseStack
            this.renderCtx.bufferSource = bufferSource
            this.renderCtx.packedLight = packedLight
            this.renderCtx.packedOverlay = packedOverlay
            this.renderCtx.partialTick = partialTick
            this.renderCtx.renderType = renderType
            this.renderCtx.buffer = buffer ?: if (renderType == null) null else bufferSource.getBuffer(renderType)
            this.renderCtx.isReRender = isReRender
            if (this.renderCtx.buffer == null) {
                return
            }

            // 1.12 passed (red, blue, green) here - kept as-is (the colour is always white)
            val renderColor = software.bernie.geckolib.util.Color(colour)
            val r = renderColor.red / 255.0f
            val g = renderColor.blue / 255.0f
            val b = renderColor.green / 255.0f
            val a = renderColor.alpha / 255.0f
            if (isReRender) {
                // a render layer redraws the model into its own buffer
                for (bone in model.topLevelBones()) {
                    this.renderRecursively(bone, r, g, b, a)
                }
            } else {
                this.renderModel(model, animatable, partialTick, r, g, b, a)
            }
        } finally {
            this.renderCtx.isReRender = false
            poseStack.popPose()
        }
    }

    /**
     * Model render entry: gated by [canRenderPlayer] / local-registration
     * / `shouldRenderModel()`, then captures the global matrix, binds the
     * entity texture, resolves active custom-part bones, updates custom bones +
     * bone offsets (`BodyParts.updateCustomBones/updateBoneOffset`) and
     * renders the buffer with early/late hooks.
     */
    open fun renderModel(model: BakedGeoModel, entity: T, partialTicks: Float, r: Float, g: Float, b: Float, a: Float) {
        val player = Minecraft.getInstance().player
        if (player == null || entity.isLocallyRegistered() || !entity.shouldRenderModel() || this.canRenderPlayer(entity, player)) {
            this.captureGlobalMatrix(entity, partialTicks, r, g, b, a)
            this.renderLate(entity, partialTicks, r, g, b, a)
            this.renderCtx.switchTexture(this.getTextureLocation(this.renderEntity))
            this.renderCtx.captureBase()
            this.activeCustomPartBones.clear()
            this.activeCustomPartBones = this.getActiveBones(entity.isLocallyRegistered(), entity.getOutfitIndex() == 0)
            this.getSkinTexture(this.renderEntity)
            val processor = entity.getAnimationProcessor() ?: this.geoModel.animationProcessor
            BodyParts.updateCustomBones(processor.registeredBones, this.getBlacklistedBones(), this)
            BodyParts.updateBoneOffset(entity, partialTicks)
            this.renderModelBuffer(model, entity, r, g, b, a, partialTicks)
            this.renderAfter(entity, partialTicks, r, g, b, a)
        }
    }

    /** GL3 `IGeoRenderer.renderLate` hook (no-op). */
    open fun renderLate(entity: T, partialTicks: Float, r: Float, g: Float, b: Float, a: Float) {
    }

    /** GL3 `IGeoRenderer.renderAfter` hook (no-op). */
    open fun renderAfter(entity: T, partialTicks: Float, r: Float, g: Float, b: Float, a: Float) {
    }

    /**
     * Renders all top-level bones except the `steve` bone (the player
     * skin body), then renders the steve bone in a second pass with
     * the interaction player's skin texture and `getRenderScaleFactor()` as alpha.
     * Subclass splits (`body2` in Manglelie) override this.
     */
    open fun renderModelBuffer(model: BakedGeoModel, entity: T, r: Float, g: Float, b: Float, a: Float, scale: Float) {
        var steveBone: GeoBone? = null

        for (bone in model.topLevelBones()) {
            if (bone.name == "steve") {
                steveBone = bone
            } else {
                this.renderRecursively(bone, r, g, b, a)
            }
        }

        this.renderRightEye()
        if (steveBone != null) {
            this.renderCtx.switchTexture(this.getSkinTexture(this.renderEntity))
            try {
                this.renderRecursively(steveBone, r, g, b, this.renderEntity.getRenderScaleFactor())
            } finally {
                this.renderCtx.resetTexture()
            }
        }
    }

    /**
     * After the model: publishes camera bones, draws the scene's custom parts and the
     * optional overlay colour (Java tail of `doRenderEntity`). Replaces GeckoLib's
     * `renderFinal` (vanilla name tag + leash), which the 1.12 renderer did not use.
     */
    override fun renderFinal(
        poseStack: PoseStack,
        animatable: T,
        model: BakedGeoModel,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer?,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        this.applyCameraBone(animatable)
        poseStack.pushPose()
        try {
            // back to the vanilla entity origin, then to the 1.12 origin (the lerped local player)
            poseStack.translate(-this.renderDelta.x, -this.renderDelta.y, -this.renderDelta.z)
            translateToPlayerOrigin(poseStack, animatable, partialTick)
            this.renderCtx.poseStack = poseStack
            this.renderCtx.bufferSource = bufferSource
            this.renderCtx.packedLight = packedLight
            SexSceneRenderer.renderSexSceneEffects(animatable, partialTick, poseStack, bufferSource, packedLight)
            val overlayColor = this.getAdditionalOverlayColor(animatable)
            if (overlayColor != null) {
                this.renderGirlColor(animatable, partialTick, overlayColor)
            }
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Publishes the world positions of every camera/attachment bone
     * ([GirlModel.CAMERA_PLACEMENTS] + the girl's own tracking list) from
     * the model matrices into the girl's bone-position cache — consumed by the
     * scene camera and effect renderers. Must run after the model render.
     */
    open fun applyCameraBone(entity: T) {
        val boneNames = ArrayList(GirlModel.CAMERA_PLACEMENTS)
        boneNames.addAll(entity.boneTrackingList)

        for (boneName in boneNames) {
            val matrixStack = entity.getBoneMatrixStack(boneName, !entity.isLocallyRegistered())
            val modelMatrix = matrixStack.getModelMatrix()
            // vecmath m03 / m13 / m23 are joml m30() / m31() / m32()
            val worldPos = Vec3(-modelMatrix.m30().toDouble(), modelMatrix.m31().toDouble(), -modelMatrix.m32().toDouble())
            entity.setBoneWorldPosition(boneName, worldPos)
        }
    }

    open fun getAdditionalOverlayColor(entity: T): Vector3fSexmodSpecial? = null

    open fun getRenderEntity(girl: BaseGirlEntity): Entity = girl

    /**
     * Draws the colored line overlay (e.g. corrupted girl tint) anchored to the
     * girl (target position when anchored, else lerped) relative to the local
     * player, with line width from [getRenderOffset].
     */
    open fun renderGirlColor(girl: BaseGirlEntity, partialTicks: Float, overlayColor: Vector3fSexmodSpecial) {
        GirlOverlayRenderer.renderGirlColor(this, girl, partialTicks, overlayColor)
    }

    /** Overlay line hook; `renderCtx.poseStack` is at the girl's overlay origin. */
    open fun drawOverlayLines(girl: BaseGirlEntity, color: Vector3fSexmodSpecial, lineWidth: Float) {
    }

    /**
     * Rotation overrides for player-girls: while the owner elytra-flies, the
     * girl pitches nose-down (clamped by flight time) and banks into the turn
     * like the owner.
     */
    override fun applyRotations(animatable: T, poseStack: PoseStack, ageInTicks: Float, rotationYaw: Float, partialTick: Float, nativeScale: Float) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale)
        if (animatable is AbstractPlayerGirlEntity) {
            val ownerUuid = animatable.getOwnerUserUUID()
            if (ownerUuid != null) {
                val owner = animatable.level().getPlayerByUUID(ownerUuid)
                if (owner != null) {
                    if (owner.isFallFlying) {
                        val elytraTicks = owner.fallFlyingTicks.toFloat() + partialTick
                        val flightProgress = Mth.clamp(elytraTicks * elytraTicks / 100.0f, 0.0f, 1.0f)
                        poseStack.mulPose(Axis.XP.rotationDegrees(flightProgress * (-90.0f - owner.xRot)))
                        val lookVec = owner.getViewVector(partialTick)
                        val motion = owner.deltaMovement
                        val motionSq = motion.x * motion.x + motion.z * motion.z
                        val lookSq = lookVec.x * lookVec.x + lookVec.z * lookVec.z
                        if (motionSq > 0.0 && lookSq > 0.0) {
                            val cosAngle = (motion.x * lookVec.x + motion.z * lookVec.z) / (Math.sqrt(motionSq) * Math.sqrt(lookSq))
                            val crossZ = motion.x * lookVec.z - motion.z * lookVec.x
                            poseStack.mulPose(Axis.YP.rotationDegrees((Math.signum(crossZ) * Math.acos(cosAngle)).toFloat() * 180.0f / Math.PI.toFloat()))
                        }
                    }
                }
            }
        }
    }

    /** Per-bone hook, called before the bone transform is pushed (pose is at the parent bone). */
    open fun onBoneProcessing(boneName: String, bone: GeoBone) {
    }

    /**
     * Vanilla-style leash render (two brown strip passes with a sagging curve)
     * between the girl and her leash holder. Coordinates are relative to the
     * current pose (the girl's render position).
     */
    open fun renderLeash(girl: BaseGirlEntity, x: Double, y: Double, z: Double, partialTicks: Float) {
        val bufferSource = this.renderCtx.bufferSource ?: return
        GirlLeashRenderer.render(girl, x, y, z, partialTicks, this.renderCtx.poseStack, bufferSource, this.renderCtx.packedLight)
    }

    // ---------------------------------------------------------------------------------
    // Bone recursion
    // ---------------------------------------------------------------------------------

    /**
     * GeckoLib entry into the bone recursion (used by GeckoLib internals and render-layer
     * re-renders): adopts the given buffer state and delegates to the mod recursion.
     */
    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: T,
        bone: GeoBone,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        this.renderEntity = animatable
        this.renderCtx.poseStack = poseStack
        this.renderCtx.bufferSource = bufferSource
        this.renderCtx.renderType = renderType
        this.renderCtx.buffer = buffer
        this.renderCtx.isReRender = isReRender
        this.renderCtx.partialTick = partialTick
        this.renderCtx.packedLight = packedLight
        this.renderCtx.packedOverlay = packedOverlay
        val c = software.bernie.geckolib.util.Color(colour)
        this.renderRecursively(bone, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
    }

    /**
     * Pushes the GL3 bone transform onto `poseStack` (translate, pivot, rotate Z/Y/X,
     * scale, back from pivot), recording GeckoLib's tracked bone matrices on the way.
     */
    open fun prepBoneMatrix(poseStack: PoseStack, bone: GeoBone) {
        RenderUtil.translateMatrixToBone(poseStack, bone)
        RenderUtil.translateToPivotPoint(poseStack, bone)
        RenderUtil.rotateMatrixAroundBone(poseStack, bone)
        RenderUtil.scaleMatrixForBone(poseStack, bone)
        if (bone.isTrackingMatrices) {
            val poseState = Matrix4f(poseStack.last().pose())
            val localMatrix = RenderUtil.invertAndMultiplyMatrices(poseState, this.entityRenderTranslations)
            bone.setModelSpaceMatrix(RenderUtil.invertAndMultiplyMatrices(poseState, this.modelRenderTranslations))
            bone.setLocalSpaceMatrix(RenderUtil.translateMatrix(localMatrix, this.getRenderOffset(this.renderEntity, 1.0f).toVector3f()))
            bone.setWorldSpaceMatrix(RenderUtil.translateMatrix(Matrix4f(localMatrix), this.renderEntity.position().toVector3f()))
        }

        RenderUtil.translateAwayFromPivotPoint(poseStack, bone)
    }

    /**
     * Recursive bone render: per bone — held-item / trade-overlay hooks,
     * forced opacity for `ballL/ballR/cock`, [onBoneProcessing],
     * armor tinting, and skipping (Head2 in first person, disallowed bones,
     * custom-part-replaced bones). Children switch to [renderCustomBones]
     * when the bone carries an armor overlay (nonzero overlay alpha).
     */
    open fun renderRecursively(bone: GeoBone, r: Float, g: Float, b: Float, a: Float) {
        if (SexWorldClient.isPreloadWorld(this.renderEntity.level())) {
            return
        }

        var red = r
        var green = g
        var blue = b
        var alpha = a
        val boneName = bone.name
        if (boneName == "weapon" && this.renderEntity is AbstractGirlNpcEntity) {
            this.renderHeldItem(bone)
        }

        if (boneName == "itemRenderer" && this.renderEntity.getCurrentAction() == Action.PAYMENT) {
            this.renderTradeOverlay(bone)
        }

        if (boneName == "ballL" || boneName == "ballR" || boneName == "cock") {
            alpha = 1.0f
        }

        this.onBoneProcessing(boneName, bone)
        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, bone)
            if ("Head2" == boneName && !this.shouldRenderHead2()) {
                return
            } else if (!this.isBoneAllowedForRender(boneName)) {
                return
            }

            if (!bone.isHidden) {
                val armorColor = this.calculateBoneArmorColor(boneName, red, green, blue)
                red = armorColor.x
                green = armorColor.y
                blue = armorColor.z
                val armorAlpha = armorColor.w.toDouble()
                if (!this.activeCustomPartBones.contains(boneName)) {
                    for (cube in bone.cubes) {
                        poseStack.pushPose()
                        try {
                            this.currentRenderingBone = bone
                            this.renderCubeGeometry(cube, red, green, blue, alpha, armorAlpha)
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }

                for (childBone in bone.childBones) {
                    if (armorAlpha == 0.0) {
                        this.renderRecursively(childBone, red, green, blue, alpha)
                    } else {
                        this.renderCustomBones(childBone, red, green, blue, alpha, armorAlpha)
                    }
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    open fun createOverlayColor(r: Float, g: Float, b: Float): Vector4f {
        return Vector4f(r, g, b, 0.0f)
    }

    /**
     * Armor bones only render on NPC girls (the player's own armor is drawn by
     * the vanilla player renderer).
     */
    open fun isBoneAllowedForRender(boneName: String): Boolean {
        return if (!boneName.startsWith("armor")) true else this.renderEntity is AbstractGirlNpcEntity
    }

    /**
     * Armor tint for `armor*` bones of NPC girls with an outfit: gold
     * (alpha 72/4096), iron/chain (144/4096), or leather tinted by the armor's
     * dye color. Returns the unchanged base color otherwise. The alpha encodes
     * the armor overlay strength (it is added to the texture U, selecting the
     * armor-material column of the texture).
     */
    open fun calculateBoneArmorColor(boneName: String, r: Float, g: Float, b: Float): Vector4f {
        var red = r
        var green = g
        var blue = b
        if (!boneName.startsWith("armor")) {
            return this.createOverlayColor(red, green, blue)
        }

        if (this.renderEntity !is AbstractGirlNpcEntity) {
            return this.createOverlayColor(red, green, blue)
        }

        if (this.renderEntity.entityDataManager.get(BaseGirlEntity.OUTFIT_INDEX) == 0) {
            return this.createOverlayColor(red, green, blue)
        }

        val modelProvider = this.geoModel
        if (modelProvider !is GirlModel<*>) {
            return this.createOverlayColor(red, green, blue)
        }

        val armorStack = modelProvider.getItemStackForBone(this.renderEntity, boneName)
        val armorItem = armorStack.item as? ArmorItem ?: return this.createOverlayColor(red, green, blue)

        val armorMaterial = armorItem.material
        var overlayStrength = 0.0f
        if (armorMaterial.`is`(ArmorMaterials.GOLD)) {
            overlayStrength = 1.0f
        } else if (armorMaterial.`is`(ArmorMaterials.CHAIN) || armorMaterial.`is`(ArmorMaterials.IRON)) {
            overlayStrength = 2.0f
        } else if (armorMaterial.`is`(ArmorMaterials.LEATHER)) {
            overlayStrength = 4.0f
            val dyeColor = DyedItemColor.getOrDefault(armorStack, DyedItemColor.LEATHER_COLOR)
            val rTint = (dyeColor shr 16 and 0xFF) / 255.0f
            val gTint = (dyeColor shr 8 and 0xFF) / 255.0f
            val bTint = (dyeColor and 0xFF) / 255.0f
            red *= rTint
            green *= gTint
            blue *= bTint
        }

        return Vector4f(red, green, blue, 72.0f * overlayStrength / 4096.0f)
    }

    /**
     * Captures the current model matrix for custom-part world-position
     * computation later in the frame.
     */
    open fun captureGlobalMatrix(entity: T, partialTicks: Float, r: Float, g: Float, b: Float, a: Float) {
    }

    /**
     * Custom-bone recursion (used for armor-overlay children and by
     * [GirlRendererBase]): like [renderRecursively] minus the
     * armor tinting, with per-cube pose pushes and forced opacity on the genital
     * bones. Skipped in the [SexWorldClient] preload world.
     */
    open fun renderCustomBones(bone: GeoBone, r: Float, g: Float, b: Float, a: Float, overlayAlpha: Double) {
        if (SexWorldClient.isPreloadWorld(this.renderEntity.level())) {
            return
        }

        var alpha = a
        val boneName = bone.name
        if (boneName == "weapon") {
            this.renderHeldItem(bone)
        }

        if (boneName == "ballL" || boneName == "ballR" || boneName == "cock") {
            alpha = 1.0f
        }

        this.onBoneProcessing(bone.name, bone)
        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, bone)
            if (!bone.isHidden) {
                if (!this.activeCustomPartBones.contains(boneName)) {
                    for (cube in bone.cubes) {
                        poseStack.pushPose()
                        try {
                            this.currentRenderingBone = bone
                            this.renderCubeGeometry(cube, r, g, b, alpha, overlayAlpha)
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }

                for (childBone in bone.childBones) {
                    this.renderCustomBones(childBone, r, g, b, alpha, overlayAlpha)
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * The `Head2` bone is hidden in first person while the girl is
     * controlled by the local player (it would clip into the view).
     */
    open fun shouldRenderHead2(): Boolean {
        return if (!this.renderEntity.isControlledByLocalPlayer()) true else ClientHooks.thirdPersonView() != 0
    }

    /**
     * Emits a cube's quads into `renderCtx.buffer`: transformed normals (mirrored on
     * zero-size faces), vertex colour from [BodyParts.getBoneWorldPosition] (fake shading
     * on custom-part bones) and the texture U shifted by `textureVOffset` (armor overlay).
     */
    open fun renderCubeGeometry(cube: GeoCube, r: Float, g: Float, b: Float, a: Float, textureVOffset: Double) {
        val poseStack = this.renderCtx.poseStack
        RenderUtil.translateToPivotPoint(poseStack, cube)
        RenderUtil.rotateMatrixAroundCube(poseStack, cube)
        RenderUtil.translateAwayFromPivotPoint(poseStack, cube)
        val normalMatrix = poseStack.last().normal()
        val poseMatrix = poseStack.last().pose()
        val bone = this.currentRenderingBone!! // java-npe: set by the caller before every cube

        for (quad in cube.quads()) {
            if (quad == null) {
                continue
            }

            val normal = normalMatrix.transform(Vector3f(quad.normal()))
            RenderUtil.fixInvertedFlatCube(cube, normal)
            val worldColor = BodyParts.getBoneWorldPosition(this, bone, Vec3(r.toDouble(), g.toDouble(), b.toDouble()), normal)
            this.emitQuad(quad, poseMatrix, normal, worldColor.x.toFloat(), worldColor.y.toFloat(), worldColor.z.toFloat(), a, textureVOffset)
        }
    }

    /**
     * Writes one quad's vertices: position through `poseMatrix`, texture U + `textureUOffset`,
     * colour clamped to 0..1 (as the 1.12 GL colour was).
     */
    open fun emitQuad(
        quad: software.bernie.geckolib.cache.`object`.GeoQuad,
        poseMatrix: Matrix4f,
        normal: Vector3f,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        textureUOffset: Double,
    ) {
        val buffer = this.renderCtx.buffer()
        val color = FastColor.ARGB32.colorFromFloat(
            Mth.clamp(a, 0.0f, 1.0f),
            Mth.clamp(r, 0.0f, 1.0f),
            Mth.clamp(g, 0.0f, 1.0f),
            Mth.clamp(b, 0.0f, 1.0f),
        )
        for (vertex in quad.vertices()) {
            val position = vertex.position()
            val matrixPos = poseMatrix.transform(Vector4f(position.x(), position.y(), position.z(), 1.0f))
            buffer.addVertex(
                matrixPos.x(), matrixPos.y(), matrixPos.z(),
                color,
                (vertex.texU() + textureUOffset).toFloat(), vertex.texV(),
                this.renderCtx.packedOverlay, this.renderCtx.packedLight,
                normal.x(), normal.y(), normal.z(),
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Items on bones
    // ---------------------------------------------------------------------------------

    /**
     * Payment item shown on the `itemRenderer` bone during a
     * `PAYMENT` action, keyed by the girl's current hand state:
     * doggy=2 diamonds, blowjob=3 emeralds, strip=1 gold ingot,
     * boobjob=2 ender pearls, touch_boobs=2 salmon (1.12 fish:1), sex=3 cod (fish:0), else none.
     */
    open fun getPaymentItemStack(): ItemStack? {
        return when (this.renderEntity.entityDataManager.get(BaseGirlEntity.GIRL_HAND_STATES)) {
            "doggy" -> ItemStack(Items.DIAMOND, 2)
            "blowjob" -> ItemStack(Items.EMERALD, 3)
            "strip" -> ItemStack(Items.GOLD_INGOT, 1)
            "boobjob" -> ItemStack(Items.ENDER_PEARL, 2)
            "touch_boobs" -> ItemStack(Items.SALMON, 2)
            "sex" -> ItemStack(Items.COD, 3)
            else -> null
        }
    }

    /**
     * Renders a vanilla item at the current pose (Java
     * `getItemRenderer().renderItem(entity, stack, THIRD_PERSON_RIGHT_HAND)`), then re-binds
     * the entity texture (Java `bindTexture(getEntityTexture(...))` + `buffer.begin`).
     */
    open fun renderVanillaItem(stack: ItemStack, displayContext: ItemDisplayContext) {
        val bufferSource = this.renderCtx.bufferSource ?: return
        Minecraft.getInstance().itemRenderer.renderStatic(
            this.renderEntity,
            stack,
            displayContext,
            false,
            this.renderCtx.poseStack,
            bufferSource,
            this.renderEntity.level(),
            this.renderCtx.packedLight,
            OverlayTexture.NO_OVERLAY,
            this.renderEntity.id,
        )
        this.renderCtx.switchTexture(this.getTextureLocation(this.renderEntity))
    }

    /**
     * Renders the payment items fanned in front of the girl on the
     * `itemRenderer` bone: per item, apply the bone pivot + rotations, fan
     * 2nd/3rd items sideways, render, then re-bind the entity texture.
     */
    open fun renderTradeOverlay(bone: GeoBone) {
        val paymentStack = this.getPaymentItemStack() ?: return
        val poseStack = this.renderCtx.poseStack

        for (i in 0 until paymentStack.count) {
            poseStack.pushPose()
            try {
                MatrixHelper.applyBoneTransform(poseStack, bone)
                // the bone rotations are radians but were fed to glRotated as degrees in 1.12 - kept
                poseStack.mulPose(Axis.ZP.rotationDegrees((bone.rotX + 2.5).toFloat()))
                poseStack.mulPose(Axis.YP.rotationDegrees(bone.rotY))
                poseStack.mulPose(Axis.XP.rotationDegrees(bone.rotZ))
                when (i) {
                    1 -> {
                        poseStack.mulPose(Axis.ZP.rotationDegrees(-15.0f))
                        poseStack.translate(0.0, 0.0, -0.025)
                    }
                    2 -> {
                        poseStack.mulPose(Axis.ZP.rotationDegrees(15.0f))
                        poseStack.translate(0.0, 0.0, 0.025)
                    }
                }

                poseStack.scale(this.renderEntity.scaleFactor, this.renderEntity.scaleFactor, this.renderEntity.scaleFactor)
                this.renderVanillaItem(ItemStack(paymentStack.item, 1), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
            } finally {
                poseStack.popPose()
            }
        }
    }

    open fun resolveHeldItemStack(stack: ItemStack?): ItemStack? = stack

    /**
     * Renders the held weapon/bow on the `weapon` bone for NPC girls:
     * weapon (attack mode 1) or bow (mode 2); a drawn bow gets an increasing
     * pull progress (item-use count) and bow-hold rotation, attacks get stab
     * (thrust offset + rotation) or slash rotations from the entity's swing
     * state. Re-binds the entity texture afterwards.
     */
    open fun renderHeldItem(bone: GeoBone) {
        val entity = this.renderEntity
        if (entity is AbstractGirlNpcEntity) {
            val dataManager = entity.entityData
            val girl: AbstractGirlNpcEntity = entity
            val attackMode = dataManager.get(AbstractGirlNpcEntity.ATTACK_MODE)
            if (girl.getCurrentAction() != Action.BOW) {
                this.bowPullProgress = 0.0f
            }

            var heldStack: ItemStack? = null
            if (attackMode == 1) {
                heldStack = dataManager.get(AbstractGirlNpcEntity.WEAPON)
            } else if (attackMode == 2) {
                heldStack = dataManager.get(AbstractGirlNpcEntity.BOW)
            }

            heldStack = this.resolveHeldItemStack(heldStack)
            if (heldStack != null) {
                if (heldStack.item === Items.BOW && girl.getCurrentAction() == Action.BOW) {
                    this.bowPullProgress += 0.015f
                    girl.setItemUseCount(Math.round(-this.bowPullProgress * 20.0f + heldStack.getUseDuration(girl)))
                    girl.setHeldItemOverride(heldStack)
                }

                val poseStack = this.renderCtx.poseStack
                poseStack.pushPose()
                try {
                    MatrixHelper.applyBoneTransform(poseStack, bone)
                    if (heldStack.item is BowItem) {
                        poseStack.mulPose(Axis.XP.rotationDegrees(girl.holdBowRot.toFloat()))
                    } else if (girl.getCurrentAction() == Action.ATTACK && girl.nextAttack == 0) {
                        poseStack.translate(girl.swordOffsetStab.x, girl.swordOffsetStab.y, girl.swordOffsetStab.z)
                        poseStack.mulPose(Axis.XP.rotationDegrees(girl.stabSwordRot.toFloat()))
                    } else {
                        poseStack.mulPose(Axis.XP.rotationDegrees(girl.slashSwordRot.toFloat()))
                    }

                    this.renderVanillaItem(heldStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                } finally {
                    poseStack.popPose()
                }
            }
        }
    }

    /**
     * Block ray-trace (voxel DDA, mirrors vanilla semantics) with NaN guards —
     * used for the occlusion check. Returns the first solid block hit or
     * `null` (NaN inputs or no hit within 200 steps).
     */
    open fun rayTraceBlocks(start: Vec3, end: Vec3, world: Level): BlockHitResult? {
        return RenderOcclusion.rayTraceBlocks(start, end, world)
    }

    companion object {
        @JvmField
        val LINE_TEXTURE: ResourceLocation = Main.rl("textures/line.png")

        const val SKIN_CACHE_CAPACITY: Int = SkinTextureCache.CAPACITY

        /**
         * Bounded LRU of tinted player skins (access order, owned by
         * [SkinTextureCache]). Evicted entries release their GPU texture,
         * except the shared fallback texture.
         */
        @JvmField
        val SKIN_TEXTURE_CACHE: LinkedHashMap<UUID, ResourceLocation> = SkinTextureCache.TEXTURES

        /** Java `protected static Minecraft mc`. */
        @JvmStatic
        val mc: Minecraft
            get() = Minecraft.getInstance()

        @Suppress("UNCHECKED_CAST")
        private fun <T : BaseGirlEntity> castModel(model: GeoModel<out BaseGirlEntity>): GeoModel<T> = model as GeoModel<T>

        /**
         * The GeckoLib model of `entity`'s renderer (Java `BaseGirlEntity.getGeoModel()`):
         * null when the entity has no [GirlRenderer]. CLIENT only (called by
         * `BaseGirlEntity.getGeoModel` when `level().isClientSide`).
         */
        @JvmStatic
        fun getGeoModelFor(entity: BaseGirlEntity): GeoModel<*>? {
            val render = Minecraft.getInstance().entityRenderDispatcher.getRenderer(entity)
            return if (render !is GirlRenderer<*>) null else render.geoModel
        }

        /**
         * Drops `uuid`'s cached tinted skin; see
         * [SkinTextureCache.invalidate] for the thread contract.
         */
        @JvmStatic
        fun invalidate(uuid: UUID?) {
            SkinTextureCache.invalidate(uuid)
        }

        /**
         * Drops every cached tinted skin (session change / skin update); see
         * [SkinTextureCache.invalidateAll].
         */
        @JvmStatic
        fun invalidateAll() {
            SkinTextureCache.invalidateAll()
        }

        /**
         * Yaw for rendering: the pinned scene yaw when anchored, else the
         * interpolated render-yaw-offset (partial ticks).
         */
        @JvmStatic
        fun getInterpolatedYaw(girl: BaseGirlEntity, partialTicks: Float): Float {
            return if (girl.isAnchored()) girl.getYawRotation() else RotationHelper.lerp(girl.yBodyRotO, girl.yBodyRot, partialTicks)
        }

        /**
         * Line width scaled by the girl's distance from the camera (5 blocks = max
         * width, closer = thinner), clamped 0..1 progress.
         */
        @JvmStatic
        fun getRenderOffset(girl: BaseGirlEntity, partialTicks: Float, minWidth: Float, maxWidth: Float): Float {
            return GirlOverlayRenderer.getRenderOffset(girl, partialTicks, minWidth, maxWidth)
        }

        /**
         * Draws a single colored line segment between two cached bone offsets
         * (at the overlay pose set up by `renderGirlColor`).
         */
        @JvmStatic
        fun renderNameLabel(girl: BaseGirlEntity, startBone: String, endBone: String, r: Float, g: Float, b: Float, width: Float) {
            GirlOverlayRenderer.renderNameLabel(girl, startBone, endBone, r, g, b, width)
        }

        /**
         * Draws the bra-string tint chain (all `braString*` segments) in the
         * given color — used by the corruption/buff overlay renderers.
         */
        @JvmStatic
        fun renderGirlTint(girl: BaseGirlEntity, color: Vector3fSexmodSpecial, width: Float) {
            GirlOverlayRenderer.renderGirlTint(girl, color, width)
        }

        /** Partial-tick position the vanilla dispatcher renders `entity` at (`xOld` → `x`). */
        @JvmStatic
        fun lerpedPosition(entity: Entity, partialTicks: Float): Vec3 {
            return Vec3(
                Mth.lerp(partialTicks.toDouble(), entity.xOld, entity.x),
                Mth.lerp(partialTicks.toDouble(), entity.yOld, entity.y),
                Mth.lerp(partialTicks.toDouble(), entity.zOld, entity.z),
            )
        }

        /**
         * Moves a pose that sits at `entity`'s vanilla render origin to the 1.12 render origin
         * (the lerped local player position), so code written against 1.12 coordinates
         * ("target position minus lerped local player") can translate as before.
         */
        @JvmStatic
        fun translateToPlayerOrigin(poseStack: PoseStack, entity: Entity, partialTicks: Float) {
            val player = Minecraft.getInstance().player ?: return
            val entityLerp = lerpedPosition(entity, partialTicks)
            val playerLerp = lerpedPosition(player, partialTicks)
            poseStack.translate(playerLerp.x - entityLerp.x, playerLerp.y - entityLerp.y, playerLerp.z - entityLerp.z)
        }
    }
}
