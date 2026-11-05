package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.UnknownScreen
import com.trolmastercard.sexmod.client.model.GalathNpcModel
import com.trolmastercard.sexmod.client.model.ManglelieNpcModel
import com.trolmastercard.sexmod.client.renderer.api.IGirlRenderer
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BodyParts
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.api.IGalath
import com.trolmastercard.sexmod.util.GalathGeometryRender
import com.trolmastercard.sexmod.util.RibbonRenderer
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.Vector2f
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for Galath (the demon/dragon girl). Beyond the standard girl
 * pipeline it renders: a flight dash trajectory
 * ([getDashPosition] + first-person dash POV), animated wing meshes and
 * star rings, hair ribbons, the coin on the coin bone during GIVE_COIN, sword
 * / pussy-licking / morning-blowjob ribbon effects, and Manglelie interaction
 * poses.
 *
 * <b>Dash.</b> [doRenderEntity] resolves the dash target position
 * (lerped to the target entity, 24..32 ticks of approach then a hold at 1.5
 * blocks) and pins it as the render target before the normal render; the POV
 * wing meshes are drawn in a second pass. This is what makes the rape/dash
 * scene look like Galath flying at the player.
 *
 * <b>Custom passes.</b> [renderModelBuffer] renders body, coin
 * (GIVE_COIN effect), steve (player skin) and body2 (Manglelie texture) bones
 * in separate texture passes; wing meshes are built from cached
 * `wingRV...`/`wingLV...` bone offsets.
 *
 * <b>Pose bones.</b> [onBoneProcessing] adds hair-follow, blowjob
 * oscillation, rape-charge arm aim and sword/tongue ribbons (see the ribbon
 * helpers).
 *
 * CLIENT-side render thread only.
 *
 * Port notes: in the deobfuscated 1.12 source four overrides had lost their
 * names and became dead code (`doRenderGalath`, `getBoneWorldPosGalath`,
 * `renderMasterbateEffect`, `getWingColor`). They are wired back as the
 * overrides of [doRenderEntity], [getBoneWorldPos], [onBoneRenderStart] and
 * [getAdditionalOverlayColor] (the original names are kept as delegates) —
 * otherwise the documented dash/POV/wing passes would never run. The static
 * POV helpers take the caller's [PoseStack]/[MultiBufferSource]; the pose must be
 * at the 1.12 render origin (lerped local player, see
 * `GirlRenderer.translateToPlayerOrigin`). Immediate-mode GL draws map to
 * buffer-source render types: textured quads -> `entityTranslucent` at full
 * bright, untextured colour quads -> `debugQuads`, the star ring -> `lineStrip`,
 * GL_TRIANGLES -> degenerate quads.
 */
open class GalathRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>, shadowOffset: Double) :
    GirlRenderer<GalathEntity>(ctx, model, shadowOffset), IGirlRenderer {
    @JvmField
    var initialized: Boolean = false

    @JvmField
    var animationProgress: Float = 0.0f

    /**
     * Wing color while Galath is corrupted (`wingsAnimated` flag): `null` in
     * the preload world or when corrupted (use default), else black
     * ([ZERO_OFFSET]).
     */
    open fun getWingColor(galath: GalathEntity): Vector3fSexmodSpecial? {
        return if (SexWorldClient.isPreloadWorld(galath.level())) {
            null
        } else {
            if (galath.wingsAnimated) null else ZERO_OFFSET
        }
    }

    override fun getAdditionalOverlayColor(entity: GalathEntity): Vector3fSexmodSpecial? = this.getWingColor(entity)

    /**
     * Merges the mod's custom-part bones + Manglelie's blacklist into the
     * static blacklist once (single-init guard), so no bone is drawn twice.
     */
    override fun getBlacklistedBones(): HashSet<String> {
        if (!this.initialized) {
            BLACKLISTED_BONES.addAll(BodyParts.CUSTOM_PART_BONES)
            BLACKLISTED_BONES.addAll(ManglelieRenderer.BLACKLISTED_BONES)
            this.initialized = true
        }

        return BLACKLISTED_BONES
    }

    override fun drawOverlayLines(girl: BaseGirlEntity, color: Vector3fSexmodSpecial, lineWidth: Float) {
        renderGirlTint(girl, color, lineWidth)
    }

    open fun renderMasterbateEffect(galath: GalathEntity) {
        if (galath.getCurrentAction() == Action.MASTERBATE) {
            val yaw = galath.getYawRotation()
            galath.setYRot(yaw)
            galath.yBodyRotO = yaw
            galath.yBodyRot = yaw
            galath.yHeadRotO = yaw
            galath.yHeadRot = yaw
        }
    }

    override fun onBoneRenderStart(entity: GalathEntity) {
        super.onBoneRenderStart(entity)
        this.renderMasterbateEffect(entity)
    }

    /**
     * Main render: resolves the dash target position (see
     * [getDashPosition]) and flight/rape-charge yaw effects, then runs
     * the normal girl pipeline followed by the dash POV (wing meshes + Galath
     * geometry) and, while hugging Manglelie, her POV render.
     */
    override fun doRenderEntity(entity: GalathEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.doRenderGalath(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    fun doRenderGalath(galath: GalathEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        val dashPos = getDashPosition(galath, partialTicks)
        if (dashPos != null) {
            galath.setTargetPositionDirect(dashPos)
        }

        galath.clientTargetPosition = dashPos
        GalathEntity.getAimYaw(galath, partialTicks)
        this.renderFlightEffect(galath)
        this.renderRapeCharge(galath)
        super.doRenderEntity(galath, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
        poseStack.pushPose()
        try {
            translateToPlayerOrigin(poseStack, galath, partialTicks)
            renderDashPov(galath, partialTicks, poseStack, bufferSource)
            if (galath.isHuggingManglelie()) {
                ManglelieRenderer.renderMangleliePov(galath, partialTicks, poseStack, bufferSource)
            }
        } finally {
            poseStack.popPose()
        }
    }

    fun renderRapeCharge(galath: GalathEntity) {
        if (galath.getCurrentAction() == Action.RAPE_CHARGE) {
            galath.yBodyRot = galath.getYawRotation()
            galath.yBodyRotO = galath.yBodyRot
        }
    }

    /**
     * Flight yaw effect: when the flight flag is set and Galath is moving, the
     * yaw follows her movement direction (atan2 of the delta); when hovering
     * it holds the last direction, so she doesn't spin while bobbing.
     */
    fun renderFlightEffect(galath: GalathEntity) {
        if (galath.entityData.get(GalathEntity.PARALYZED)) {
            val lastPos = Vec3(galath.xOld, galath.yOld, galath.zOld)
            val motion = galath.position().subtract(lastPos)
            val isHovering = Math.abs(motion.x) + Math.abs(motion.z) < 0.05f
            if (isHovering) {
                galath.yBodyRot = this.animationProgress
                galath.yBodyRotO = this.animationProgress
            } else {
                val yaw = (TrigMath.sinDegrees(Math.atan2(motion.z, motion.x)) - 90.0).toFloat()
                galath.yBodyRot = yaw
                galath.yBodyRotO = yaw
                this.animationProgress = yaw
            }
        }
    }

    /**
     * Galath's model pass: renders body, then the coin bone effect (GIVE_COIN),
     * then the steve (skin) bone and the body2 bone with Manglelie's texture —
     * each in its own texture pass.
     */
    override fun renderModelBuffer(model: BakedGeoModel, entity: GalathEntity, r: Float, g: Float, b: Float, a: Float, scale: Float) {
        if (model.topLevelBones().isEmpty()) {
            return
        }

        val topBone = model.topLevelBones()[0]
        var bodyBone: GeoBone? = null
        var coinBone: GeoBone? = null
        var steveBone: GeoBone? = null
        var body2Bone: GeoBone? = null

        for (bone in topBone.childBones) {
            when (bone.name) {
                "steve" -> steveBone = bone
                "body" -> bodyBone = bone
                "coin" -> coinBone = bone
                "body2" -> body2Bone = bone
            }
        }

        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            this.prepBoneMatrix(poseStack, topBone)
            if (bodyBone != null) {
                this.renderRecursively(bodyBone, r, g, b, a)
            }

            this.renderBoneEffect(coinBone, entity, scale)
            if (steveBone != null) {
                this.renderCtx.switchTexture(this.getSkinTexture(this.renderEntity))
                this.renderRecursively(steveBone, r, g, b, this.renderEntity.getRenderScaleFactor())
            }

            if (body2Bone != null) {
                this.renderCtx.switchTexture(ManglelieNpcModel.MANGLELIE_TEXTURE)
                this.renderRecursively(body2Bone, r, g, b, this.renderEntity.getRenderScaleFactor())
            }
        } finally {
            this.renderCtx.resetTexture()
            poseStack.popPose()
        }
    }

    /**
     * Pose-bone processing: hair follows the head pitch (hairBack,
     * hairDownSideL/R), blowjob/morning-pose oscillations on head/head3/iris
     * bones, the sword held during sword actions (`weapon` bone when
     * `renderSword`), tongue/mangTongue ribbon effects, rape-charge arm aim at the
     * target, and — while hugging Manglelie — her skirt-follow transform.
     */
    override fun onBoneProcessing(boneName: String, bone: GeoBone) {
        val mc = Minecraft.getInstance()
        when (boneName) {
            "hairBack" -> if (!mc.isPaused) {
                val headBone2 = this.renderEntity.getAnimationProcessor()?.getBone("head")
                if (headBone2 != null) {
                    val headPitchDegrees2 = TrigMath.toDegrees(headBone2.rotX)
                    if (headPitchDegrees2 < 0.0f) {
                        bone.setRotX(TrigMath.wrapDegrees(-headPitchDegrees2))
                    } else {
                        val tiltProgress = Math.min(1.0f, headPitchDegrees2 / 45.0f)
                        bone.setRotX(TrigMath.wrapDegrees(-headPitchDegrees2))
                        // hairBack has no animation tracks: offset from the rest pose so layer redraws don't stack it
                        bone.setPosY(bone.initialSnapshot.offsetY + tiltProgress * 1.5f)
                    }
                }
            }
            "hairDownSideL", "hairDownSideR" -> if (!mc.isPaused) {
                val headBone = this.renderEntity.getAnimationProcessor()?.getBone("head")
                if (headBone != null) {
                    val headPitchDegrees = TrigMath.toDegrees(headBone.rotX)
                    if (headPitchDegrees < 0.0f) {
                        bone.setRotX(TrigMath.wrapDegrees(-headPitchDegrees / 2.0f))
                    } else {
                        val tiltProgress2 = Math.min(1.0f, headPitchDegrees / 45.0f)
                        bone.setRotX(TrigMath.wrapDegrees(-headPitchDegrees))
                        bone.setPosY(bone.initialSnapshot.offsetY + tiltProgress2)
                    }
                }
            }
            "head" -> {
                this.handleBlowjobBone(bone)
                // 1.12 computed an aim pitch here (FLY/ATTACK_SWORD) and never used it
            }
            "weapon" -> if (this.renderEntity.renderSword) {
                val poseStack = this.renderCtx.poseStack
                poseStack.pushPose()
                try {
                    MatrixHelper.applyBoneTransform(poseStack, bone)
                    poseStack.scale(1.5f, 1.0f, 2.0f)
                    poseStack.translate(0.0, 0.0, 0.05)
                    poseStack.mulPose(Axis.XP.rotationDegrees(110.0f))
                    this.renderVanillaItem(ItemStack(Items.IRON_SWORD), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                } finally {
                    poseStack.popPose()
                }
            }
            "tongue" -> this.renderPussyLickingBone(bone)
            "mangTongue" -> this.renderMorningBlowjobBone(bone)
            "head3" -> this.handleMorningPose(bone)
            "irisL", "irisR" -> this.handleMorningBlowjob(bone)
            "irsisFaceR2", "irsisFaceR3" -> this.handleBlowjobState(bone)
            "armL", "armR" -> if (this.renderEntity.getCurrentAction() == Action.RAPE_CHARGE) {
                val target = this.renderEntity.getTargetEntity()
                if (target != null) {
                    val yaw = this.renderEntity.yBodyRot
                    var delta = target.position().subtract(this.renderEntity.position())
                    delta = VectorMath.rotateByYaw(delta, yaw)
                    val clampedX = -ThreadNames.clampDouble(delta.x, -1.0, 1.0)
                    bone.setRotZ(bone.rotZ + TrigMath.toRadians(45.0 * clampedX))
                }
            }
        }

        if (this.renderEntity.isHuggingManglelie()) {
            ManglelieRenderer.applyBoneTransform(this.renderEntity, boneName, bone, true)
        }
    }

    /**
     * Tongue-bone effect: pussy-licking/masturbating actions draw the sword
     * ribbon; the morning-blowjob draws its own ribbon.
     */
    fun renderPussyLickingBone(bone: GeoBone) {
        if (Action.isAnyAction(this.renderEntity, Action.PUSSY_LICKING, Action.MASTERBATE_SITTING)) {
            this.renderSwordBone(bone)
        } else if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW)) {
            this.renderBoneBlowjob(bone)
        }
    }

    fun renderMorningBlowjobBone(bone: GeoBone) {
        if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW) || this.renderEntity.bedSoftAnimation) {
            val fade = if (this.renderEntity.bedSoftAnimation) {
                1.0f - Math.min(0.29f, Action.getActionTickSeconds(this.renderEntity, partialTick())) / 0.29f
            } else {
                1.0f
            }
            // 1.12 rebound MANGLELIE_TEXTURE afterwards; renderBoneRibbon restores the current pass texture
            this.renderBoneAction(bone, fade)
        }
    }

    /**
     * Sway for the morning-blowjob head pose: slow sine on yaw/pitch, scaled
     * down while the intro fades in.
     */
    fun handleMorningPose(bone: GeoBone) {
        if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW, Action.MORNING_BLOWJOB_FAST)) {
            if (!Minecraft.getInstance().isPaused) {
                val time = ticks()
                val yawSway = (Math.sin((time * 0.1f).toDouble()) * 0.1f).toFloat() + 0.2f
                val pitchSway = Math.sin((time * 0.1f).toDouble()).toFloat() * 0.1f
                if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW)) {
                    bone.setRotY(bone.rotY + yawSway)
                    bone.setRotZ(bone.rotZ + pitchSway)
                } else if (this.renderEntity.bedSoftAnimation) {
                    val fade = 1.0f - Math.min(0.5f, Action.getActionTickSeconds(this.renderEntity, partialTick())) / 0.5f
                    bone.setRotY(bone.rotY + yawSway * fade)
                    bone.setRotZ(bone.rotZ + pitchSway * fade)
                }
            }
        }
    }

    /** Head sway for the morning-blowjob (counter-sine to the head3 pose). */
    fun handleBlowjobBone(bone: GeoBone) {
        if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW, Action.MORNING_BLOWJOB_FAST)) {
            if (!Minecraft.getInstance().isPaused) {
                val time = ticks()
                val yawSway = Math.sin((time * -0.1f).toDouble()).toFloat() * 0.1f
                val pitchSway = Math.sin((time * 0.1f).toDouble()).toFloat() * 0.1f
                if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW)) {
                    bone.setRotY(bone.rotY + yawSway)
                    bone.setRotZ(bone.rotZ + pitchSway)
                } else if (this.renderEntity.bedSoftAnimation) {
                    val fade = Math.min(0.5f, Action.getActionTickSeconds(this.renderEntity, partialTick())) / 0.5f
                    bone.setRotY(bone.rotY + yawSway * fade)
                    bone.setRotZ(bone.rotZ + pitchSway * fade)
                }
            }
        }
    }

    fun handleMorningBlowjob(bone: GeoBone) {
        if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW)) {
            if (!Minecraft.getInstance().isPaused) {
                val time = ticks()
                bone.setPosX((bone.posX + Math.sin((time * 0.1f).toDouble()) * -0.1f).toFloat())
            }
        }
    }

    fun handleBlowjobState(bone: GeoBone) {
        if (Action.isAnyAction(this.renderEntity, Action.MORNING_BLOWJOB_SLOW)) {
            if (!Minecraft.getInstance().isPaused) {
                val time = ticks()
                bone.setPosX((bone.posX + Math.sin((time * 0.1f).toDouble()) * -0.15f).toFloat())
            }
        }
    }

    /**
     * Ribbon animation driven by the action's time scale: length/width pulse
     * with a phase-offset cosine (used for the sword/licking effects).
     */
    fun renderBoneAction(bone: GeoBone, fadeScale: Float) {
        val timeScale = Action.getActionTimeScale(this.renderEntity, partialTick())
        val pulseWidth = fadeScale * (0.02f * (-0.4f * Math.cos((Math.PI * 2) * timeScale + 1.05) + 0.6f)).toFloat()
        val config = RibbonRenderer.RibbonConfig(
            RIBBON_COLOR_A,
            0.0f,
            12,
            pulseWidth,
            { progress, _ -> fadeScale * (Math.cos((Math.PI * 2) * timeScale + 0.35f + -0.2f * progress) * -10.0).toFloat() },
            { _, _ -> 0.0f },
            { progress, _ -> fadeScale * (Math.cos((Math.PI * 2) * timeScale + 1.25 + -0.1f * progress) * -5.0).toFloat() },
            0.03f,
            0.005f,
        )
        this.renderBoneRibbon(bone, config)
    }

    fun renderBoneBlowjob(bone: GeoBone) {
        val timeScale = Action.getActionTimeScale(this.renderEntity, partialTick())
        val config = RibbonRenderer.RibbonConfig(
            RIBBON_COLOR_A,
            0.0f,
            12,
            0.02f,
            { progress, _ -> (Math.cos((Math.PI * 2) * timeScale + -0.2f * progress) * 15.0).toFloat() },
            { progress, _ -> (Math.cos((Math.PI * 2) * timeScale + -0.2f * progress) * 5.0).toFloat() },
            { _, _ -> 0.0f },
            0.03f,
            0.005f,
        )
        this.renderBoneRibbon(bone, config)
    }

    /**
     * Sword ribbon: full config at progress 0, shrunk to nothing at progress 1,
     * interpolated in between — matches the sword attack swing.
     */
    fun renderSwordBone(bone: GeoBone) {
        val progress = this.renderEntity.getSwordAttackProgress(partialTick())
        if (progress == 0.0f) {
            this.renderBoneRibbon(bone, RIBBON_CONFIG_A)
        } else if (progress == 1.0f) {
            this.renderBoneRibbon(bone, RIBBON_CONFIG_B)
        } else {
            val config = RIBBON_CONFIG_A.copy()
            config.length = RotationHelper.lerp(RIBBON_CONFIG_A.length, 0.0f, progress)
            config.width = RotationHelper.lerp(RIBBON_CONFIG_A.width, 0.0f, progress)
            this.renderBoneRibbon(bone, config)
        }
    }

    /** Renders a ribbon at the bone: apply the bone transform, draw the ribbon mesh, restore the buffer. */
    fun renderBoneRibbon(bone: GeoBone, config: RibbonRenderer.RibbonConfig) {
        val poseStack = this.renderCtx.poseStack
        val bufferSource = this.renderCtx.bufferSource ?: return
        poseStack.pushPose()
        try {
            MatrixHelper.applyBoneTransform(poseStack, bone)
            RibbonRenderer.renderRibbon(poseStack, bufferSource, Minecraft.getInstance(), config)
        } finally {
            poseStack.popPose()
            this.renderCtx.restoreBuffer()
        }
    }

    /**
     * GIVE_COIN effect on the coin bone: renders the coin cubes, then its child
     * with a full-bright lightmap that lerps 120->240 over the action's
     * 105..125-tick window while the coin color lerps dark->bright, then
     * restores the lightmap.
     */
    fun renderBoneEffect(coinBone: GeoBone?, galath: GalathEntity, partialTicks: Float) {
        if (galath.getCurrentAction() == Action.GIVE_COIN && coinBone != null) {
            val poseStack = this.renderCtx.poseStack
            val lastLight = this.renderCtx.packedLight
            poseStack.pushPose()
            try {
                this.prepBoneMatrix(poseStack, coinBone)
                if (!this.activeCustomPartBones.contains(coinBone.name)) {
                    for (cube in coinBone.cubes) {
                        poseStack.pushPose()
                        this.currentRenderingBone = coinBone
                        this.renderCubeGeometry(cube, 1.0f, 1.0f, 1.0f, 1.0f, 0.0)
                        poseStack.popPose()
                    }
                }

                val childBone = coinBone.childBones.firstOrNull() ?: return
                val coinTicks = ThreadNames.clampFloat(Action.GIVE_COIN.ticksPlaying[1] + partialTicks, 105.0f, 125.0f)
                val progress = (coinTicks - 105.0f) / 20.0f
                val lightmap = RotationHelper.lerp(120.0f, 240.0f, progress).toInt()
                val coinColor = RotationHelper.lerpVector3f(GalathCoinRenderer.COIN_COLOR_DARK, GalathCoinRenderer.COIN_COLOR, progress.toDouble())
                this.renderCtx.packedLight = lightmap or (lightmap shl 16)
                poseStack.pushPose()
                this.prepBoneMatrix(poseStack, childBone)
                if (!this.activeCustomPartBones.contains(childBone.name)) {
                    for (cube2 in childBone.cubes) {
                        poseStack.pushPose()
                        this.currentRenderingBone = childBone
                        this.renderCubeGeometry(cube2, coinColor.x, coinColor.y, coinColor.z, 1.0f, 0.0)
                        poseStack.popPose()
                    }
                }

                poseStack.popPose()
            } finally {
                poseStack.popPose()
                this.renderCtx.packedLight = lastLight
            }
        }
    }

    /**
     * Pins all yaw fields to the movement yaw while running, so Galath faces
     * her travel direction.
     */
    fun getBoneWorldPosGalath(galath: GalathEntity, partialTicks: Float, pos: Vec3): Vec3 {
        if (galath.getCurrentAction() == Action.RUN) {
            val yaw = galath.getYawRotation()
            galath.setYRot(yaw)
            galath.yBodyRotO = yaw
            galath.yBodyRot = yaw
            galath.yHeadRotO = yaw
            galath.yHeadRot = yaw
        }

        return pos
    }

    override fun getBoneWorldPos(entity: GalathEntity, partialTicks: Float, pos: Vec3): Vec3 =
        this.getBoneWorldPosGalath(entity, partialTicks, super.getBoneWorldPos(entity, partialTicks, pos))

    private fun partialTick(): Float = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(true)

    private fun ticks(): Float = (Minecraft.getInstance().player?.tickCount ?: 0) + partialTick()

    companion object {
        const val WING_VERTICES_COUNT: Int = 14

        @JvmField
        val BLACKLISTED_BONES: HashSet<String> = hashSetOf(
            "static", "turnable", "slip", "boobs", "booty", "vagina", "fuckhole", "futaBallLR", "futaBallLL", "coin", "pentagram",
        )

        @JvmField
        val ZERO_OFFSET: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.0f, 0.0f)

        @JvmField
        val RIBBON_COLOR_A: UnknownScreen = UnknownScreen(152, 45, 62, 255)

        @JvmField
        val RIBBON_COLOR_B: UnknownScreen = UnknownScreen(84, 66, 88, 255)

        @JvmField
        val TEXTURE_UV_A: Vector2f = Vector2f(0.25f, 0.125f)

        @JvmField
        val TEXTURE_UV_B: Vector2f = Vector2f(0.375f, 0.125f)
        const val TEXTURE_UV_HEIGHT: Float = 0.125f

        @JvmField
        val STAR_TEXTURE: ResourceLocation = Main.rl("textures/star.png")

        @JvmField
        val RIBBON_CONFIG_A: RibbonRenderer.RibbonConfig = RibbonRenderer.RibbonConfig(
            RIBBON_COLOR_A, 0.1f, 12, 0.035f,
            { progress, time -> (Math.sin(time * 0.3 + -0.2 * progress) * 15.0).toFloat() },
            { progress, time -> (Math.sin(time * -0.15 + -0.2 * progress) * 3.0).toFloat() },
            { _, _ -> 0.0f },
            0.03f, 0.005f,
        )

        @JvmField
        val RIBBON_CONFIG_B: RibbonRenderer.RibbonConfig = RibbonRenderer.RibbonConfig(
            RIBBON_COLOR_A, 0.0f, 12, 0.0f,
            { progress, time -> (Math.sin(time * 0.3 + -0.2 * progress) * 15.0).toFloat() },
            { progress, time -> (Math.sin(time * -0.15 + -0.2 * progress) * 3.0).toFloat() },
            { _, _ -> 0.0f },
            0.03f, 0.005f,
        )

        private fun gameTime(): Long = Minecraft.getInstance().level?.gameTime ?: 0L

        /** Full-bright, overlay-free textured vertex (the 1.12 POSITION_TEX_COLOR draws had lighting disabled). */
        private fun texVertex(vc: VertexConsumer, pose: PoseStack.Pose, x: Double, y: Double, z: Double, u: Float, v: Float, r: Int, g: Int, b: Int, a: Int) {
            vc.addVertex(pose, x.toFloat(), y.toFloat(), z.toFloat()).setColor(r, g, b, a).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0f, 1.0f, 0.0f)
        }

        /**
         * Dash trajectory: for dash progress 24..32 lerps from the current position
         * to a point 3 blocks behind the target's eyes (8-tick window), for 32..54
         * holds 1.5 blocks behind the target; otherwise `null`. Also arms
         * the dash timing fields. A sword progress of -1 resets and disables the dash.
         */
        @JvmStatic
        fun getDashPosition(galath: GalathEntity, partialTicks: Float): Vec3? {
            val dashProgress = galath.getSwordAttackProgress().toFloat()
            if (dashProgress == -1.0f) {
                galath.dashStartTick = -1L
                galath.dashEndTick = -1L
                return null
            }

            val target = galath.getTargetEntity() ?: return null

            val targetPos = RotationHelper.lerpVec3dDouble(Vec3(target.xo, target.yo, target.zo), target.position(), partialTicks.toDouble())
            if (dashProgress == 24.0f && galath.dashStartTick == -1L) {
                galath.dashStartTick = gameTime()
                galath.dashEndTick = galath.dashStartTick + 8L
            }

            if (ThreadNames.isBetween(dashProgress.toDouble(), 24.0, 32.0)) {
                val approachOffset = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 3.0), galath.getYawRotation() + 180.0f)
                val startPos = galath.getFlightTargetPos()
                val endPos = targetPos.add(0.0, target.eyeHeight.toDouble(), 0.0).add(approachOffset)
                val dashProgressT = (gameTime().toFloat() + partialTicks - galath.dashStartTick.toFloat()) / (galath.dashEndTick - galath.dashStartTick).toFloat()
                return RotationHelper.lerpVec3dDouble(startPos, endPos, dashProgressT.toDouble())
            } else if (ThreadNames.isBetween(dashProgress.toDouble(), 32.0, 54.0)) {
                val holdOffset = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 1.5), galath.getYawRotation() + 180.0f)
                return targetPos.add(holdOffset)
            } else {
                return null
            }
        }

        /**
         * First-person dash POV: draws the Galath geometry, wing effect, wing
         * geometry and wing mesh at the local player. This is what the player
         * sees during the dash scene. The pose must be at the 1.12 render origin.
         */
        @JvmStatic
        fun renderDashPov(girl: BaseGirlEntity, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource) {
            val mc = Minecraft.getInstance()
            if (mc.player != null) {
                poseStack.pushPose()
                try {
                    GalathGeometryRender.renderGalathGeometry(mc, girl, partialTicks, poseStack)
                    renderWingEffect(girl, poseStack, bufferSource, RotationHelper.lerp(girl.yBodyRotO, girl.yBodyRot, partialTicks))
                    renderWingGeometry(girl, poseStack, bufferSource, partialTicks)
                    renderWingMesh(girl, poseStack, bufferSource)
                } finally {
                    poseStack.popPose()
                }
            }
        }

        /**
         * Renders the wing star ring (line circle + star quads at the `stars`
         * bone) rotating with world time — the visual effect of the wing
         * animation. Skipped when the effects flag is hidden.
         */
        @JvmStatic
        fun renderWingGeometry(girl: BaseGirlEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, partialTicks: Float) {
            if (girl is GalathEntity) {
                if (girl.entityData.get(GalathEntity.PARALYZED)) {
                    if (!girl.entityData.get(GalathEntity.HIDE_EFFECTS_FLAG)) {
                        poseStack.pushPose()
                        try {
                            val starsOffset = girl.getCachedBoneOffset("stars")
                            poseStack.translate(starsOffset.x, starsOffset.y, starsOffset.z)
                            val time = gameTime().toFloat() + partialTicks
                            val rotX = (Math.sin(time * 0.2) * 5.0).toFloat()
                            val rotZ = (Math.cos(time * 0.2) * 5.0).toFloat()
                            val rotY = (time * 3.0).toFloat()
                            poseStack.mulPose(Axis.XP.rotationDegrees(rotX))
                            poseStack.mulPose(Axis.YP.rotationDegrees(rotY))
                            poseStack.mulPose(Axis.ZP.rotationDegrees(rotZ))
                            var step = TrigMath.toRadians(9.0)
                            val starColor = GalathEntity.STAR_COLOR
                            // PORT-GAP: glLineWidth(getRenderOffset(girl, pt, 1, 3)) — core-profile line width comes from the render type
                            val lines = bufferSource.getBuffer(RenderType.lineStrip())
                            val pose = poseStack.last()
                            var angle = 0.0f
                            while (angle < Math.PI * 2) {
                                val sinAngle = Math.sin(angle.toDouble()) * 0.3f
                                val cosAngle = Math.cos(angle.toDouble()) * 0.3f
                                lines.addVertex(pose, sinAngle.toFloat(), 0.0f, cosAngle.toFloat())
                                    .setColor(starColor.x, starColor.y, starColor.z, 1.0f)
                                    .setNormal(pose, Math.cos(angle.toDouble()).toFloat(), 0.0f, -Math.sin(angle.toDouble()).toFloat())
                                angle += step
                            }

                            val stars = bufferSource.getBuffer(RenderType.entityTranslucent(STAR_TEXTURE))
                            step = TrigMath.toRadians(60.0)
                            var angle2 = 0.0f
                            while (angle2 < Math.PI * 2) {
                                val sinAngle2 = Math.sin(angle2.toDouble()) * 0.3f
                                val cosAngle2 = Math.cos(angle2.toDouble()) * 0.3f
                                texVertex(stars, pose, sinAngle2 - 0.1f, 0.1, cosAngle2, 0.0f, 0.0f, 255, 255, 255, 255)
                                texVertex(stars, pose, sinAngle2 + 0.1f, 0.1, cosAngle2, 1.0f, 0.0f, 255, 255, 255, 255)
                                texVertex(stars, pose, sinAngle2 + 0.1f, -0.1, cosAngle2, 1.0f, 1.0f, 255, 255, 255, 255)
                                texVertex(stars, pose, sinAngle2 - 0.1f, -0.1, cosAngle2, 0.0f, 1.0f, 255, 255, 255, 255)
                                angle2 += step
                            }
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }
            }
        }

        /**
         * Renders the hair-strand ribbon mesh (two 3-point ribbons along the head's
         * hair bones) — the trailing hair effect while flying. Skipped during the
         * coin-giving action past its first 100 ticks.
         */
        @JvmStatic
        fun renderWingEffect(girl: BaseGirlEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, partialTicks: Float) {
            if (girl.getCurrentAction() != Action.GIVE_COIN || Action.GIVE_COIN.ticksPlaying[1] <= 100) {
                val buffer = bufferSource.getBuffer(RenderType.debugQuads())
                val rightMesh = GalathGeometryRender.buildBodyBoneMesh(
                    girl, partialTicks, "hairStrandStartR", "hairStrandMidR", "hairStrandEndR", 0.0296875f, 0.06484375f, 0.026124999f, 0.0570625f, "head",
                )
                val leftMesh = GalathGeometryRender.buildBodyBoneMesh(
                    girl, partialTicks, "hairStrandStartL", "hairStrandMidL", "hairStrandEndL", 0.0296875f, 0.06484375f, 0.026124999f, 0.0570625f, "head",
                )
                GalathGeometryRender.renderMesh(poseStack, buffer, rightMesh, RIBBON_COLOR_B)
                GalathGeometryRender.renderMesh(poseStack, buffer, leftMesh, RIBBON_COLOR_B)
            }
        }

        /**
         * Renders the animated wing mesh: two 14-point strips built from the
         * cached `wingRV<0..13>`/`wingLV<0..13>` bone offsets, drawn
         * with the Galath texture. Only when wings are animated.
         */
        @JvmStatic
        fun renderWingMesh(girl: BaseGirlEntity, poseStack: PoseStack, bufferSource: MultiBufferSource) {
            // ManglelieRenderer also calls this; she has no wing bones (1.12 never reached this path)
            if (girl is IGalath && girl.areWingsAnimated()) {
                val rightWing = Array(14) { i -> girl.getCachedBoneOffset("wingRV$i") }
                val leftWing = Array(14) { i -> girl.getCachedBoneOffset("wingLV$i") }
                val buffer = bufferSource.getBuffer(RenderType.entityTranslucent(GalathNpcModel.GALATH_TEXTURE))
                renderLineStrip(buffer, poseStack, rightWing)
                renderLineStrip(buffer, poseStack, leftWing)
            }
        }

        /**
         * Emits the two wing strip triangles (0-1-2, 11-12-13; as degenerate quads)
         * and the two quads 3..10 with the ribbon texture UVs.
         */
        @JvmStatic
        fun renderLineStrip(buffer: VertexConsumer, poseStack: PoseStack, points: Array<Vec3>) {
            val pose = poseStack.last()
            val ax = TEXTURE_UV_A.x
            val ay = TEXTURE_UV_A.y
            val bx = TEXTURE_UV_B.x
            val by = TEXTURE_UV_B.y
            fun p(i: Int, u: Float, v: Float) = texVertex(buffer, pose, points[i].x, points[i].y, points[i].z, u, v, 255, 255, 255, 255)
            // GL_TRIANGLES 0-1-2 and 11-12-13
            p(0, ax, ay); p(1, ax + 0.125f, ay); p(2, ax + 0.125f, ay + 0.125f); p(2, ax + 0.125f, ay + 0.125f)
            p(11, ax, ay); p(12, ax + 0.125f, ay); p(13, ax + 0.125f, ay + 0.125f); p(13, ax + 0.125f, ay + 0.125f)
            // GL_QUADS 3..10
            p(3, bx, by + 0.125f); p(4, bx, by); p(5, bx + 0.125f, by); p(6, bx + 0.125f, by + 0.125f)
            p(7, bx, by + 0.125f); p(8, bx, by); p(9, bx + 0.125f, by); p(10, bx + 0.125f, by + 0.125f)
        }
    }
}
