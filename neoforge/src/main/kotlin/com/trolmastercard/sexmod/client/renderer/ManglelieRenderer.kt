package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.gui.UnknownScreen
import com.trolmastercard.sexmod.client.model.ManglelieNpcModel
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BodyParts
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.ManglelieEntity
import com.trolmastercard.sexmod.util.EntityLookVectorHelper
import com.trolmastercard.sexmod.util.GalathGeometryRender
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for Manglelie (the corrupted girl). Beyond the standard
 * [GirlRenderer] pipeline it adds: a first-person "POV" view of the wing
 * mesh while Manglelie looks at Galath ([renderMangleliePov]), a
 * hand-rendered corruption skirt/ribbon mesh built from cached skirt bone
 * offsets, held-bow rendering on the weapon/offhand bones with corruption
 * progress, and pose coupling to her Galath partner (aim yaw, ride/head
 * actions, threesome mode).
 *
 * <b>Rendering gates.</b> [doRenderEntity] skips the standard render
 * while Manglelie is being looked at, riding mommy, or corrupting — those
 * poses are drawn by the POV/interaction paths instead. The shadow pass is
 * also skipped while she looks at Galath or corrupts.
 *
 * <b>Threesome.</b> [renderModelBuffer] switches to a two-bone pass
 * (`body2` + `steve`) instead of the normal recursion.
 *
 * CLIENT-side render thread only.
 *
 * Port notes: like [GalathRenderer], the deobfuscated `doRenderManglelie` and
 * `getBoneWorldPosManglelie` had lost their override names; they are wired back
 * as [doRenderEntity] and [getBoneWorldPos]. `doRenderShadowAndFire` maps to
 * [getShadowRadius] (0 while hidden); 1.21 has no per-renderer fire hook
 * (PORT-GAP, fire still renders). Static POV helpers take the caller's pose
 * stack at the 1.12 origin and the buffer source.
 */
open class ManglelieRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>, shadowOffset: Double) :
    GirlRenderer<ManglelieEntity>(ctx, model, shadowOffset) {
    @JvmField
    var initialized: Boolean = false

    /**
     * Merges the mod's custom-part bones into the static blacklist on first
     * use (single-init guard), so the corruption bones + custom parts are never
     * drawn twice.
     */
    override fun getBlacklistedBones(): HashSet<String> {
        if (!this.initialized) {
            BLACKLISTED_BONES.addAll(BodyParts.CUSTOM_PART_BONES)
            this.initialized = true
        }

        return BLACKLISTED_BONES
    }

    override fun doRenderEntity(entity: ManglelieEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.doRenderManglelie(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    /**
     * Gate for the standard render: skipped while Manglelie is being looked at
     * by Galath, ready to ride mommy's head, being looked at (distance pose),
     * or already riding mommy — those cases render through the POV/interaction
     * paths (see class doc).
     */
    fun doRenderManglelie(manglelie: ManglelieEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        if (!this.isManglelieLooking(manglelie)) {
            if (!this.canRideMommy(manglelie)) {
                if (!isManglelieLooking(manglelie, 0.5f)) {
                    if (!this.isRidingMommy(manglelie)) {
                        super.doRenderEntity(manglelie, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
                        poseStack.pushPose()
                        try {
                            translateToPlayerOrigin(poseStack, manglelie, partialTicks)
                            renderMangleliePov(manglelie, partialTicks, poseStack, bufferSource)
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }
            }
        }
    }

    fun isRidingMommy(manglelie: ManglelieEntity): Boolean {
        val galath = manglelie.getGalathPartner(false) ?: return false

        return when (galath.getCurrentAction()) {
            Action.CONTROLLED_FLIGHT, Action.BOOST -> true
            else -> false
        }
    }

    fun canRideMommy(manglelie: ManglelieEntity): Boolean =
        if (manglelie.getCurrentAction() != Action.RIDE_MOMMY_HEAD) false else manglelie.getGalathPartner(false) == null

    fun isManglelieLooking(manglelie: ManglelieEntity): Boolean {
        val galath = manglelie.getGalathPartner(false)
        return if (galath == null) {
            false
        } else if (galath.isRemoved) {
            manglelie.setGalathPartnerUUID(null)
            false
        } else {
            galath.isHuggingManglelie()
        }
    }

    /**
     * Skips the shadow pass while Manglelie looks at Galath or corrupts
     * (1.12 `doRenderShadowAndFire`).
     */
    override fun getShadowRadius(entity: ManglelieEntity): Float {
        if (this.isManglelieLooking(entity) || entity.isCorrupting()) {
            return 0.0f
        }

        return super.getShadowRadius(entity)
    }

    /**
     * Per-bone processing: applies the skirt-follow transform (see
     * [applyBoneTransform]) and, while the girl looks at her corrupt
     * entity, swaps the held-bow rendering between the weapon bone (looking)
     * and the offhand bone (not looking).
     */
    override fun onBoneProcessing(boneName: String, bone: GeoBone) {
        applyBoneTransform(this.renderEntity, boneName, bone, false)
        val corruptEntity = this.renderEntity.getCorruptEntity()
        if (corruptEntity != null) {
            val partialTicks = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(true)
            if ("weapon" == boneName && this.renderEntity.isLookingAtGalathEntity(corruptEntity, partialTicks)) {
                this.renderEquippedItem(bone, true)
            }

            if ("offhand" == boneName && !this.renderEntity.isLookingAtGalathEntity(corruptEntity, partialTicks)) {
                this.renderEquippedItem(bone, false)
            }
        }
    }

    /**
     * Renders the corruption bow at the given bone: applies the bone transform,
     * poses the bow, and drives the entity's hand-active state from the
     * corruption progress — charging the bow (11 item-use ticks, ease-in-out)
     * while corrupting below 1.0, otherwise clearing the hand.
     */
    fun renderEquippedItem(bone: GeoBone, isOffhand: Boolean) {
        val poseStack = this.renderCtx.poseStack
        poseStack.pushPose()
        try {
            MatrixHelper.applyBoneTransform(poseStack, bone)
            if (isOffhand) {
                poseStack.translate(-0.01, 0.0, 0.0)
                poseStack.mulPose(Axis.XP.rotationDegrees(120.0f))
            } else {
                poseStack.translate(0.15, 0.0, -0.05)
                poseStack.mulPose(Axis.XP.rotationDegrees(-140.0f))
            }

            poseStack.scale(0.7f, 0.7f, 0.7f)
            val bowStack = ItemStack(Items.BOW)
            val corruptProgress = this.renderEntity.getCorruptProgress(Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(true))
            if (corruptProgress < 1.0f) {
                val easedProgress = RotationHelper.easeInOutQuad(corruptProgress.toDouble()).toFloat()
                this.renderEntity.setItemUseCount((11.0f * (1.0f - easedProgress) + 71980.0f).toInt())
                this.renderEntity.setHeldItemOverride(bowStack)
                // 1.12 setActiveHand(MAIN_HAND) + setHandActiveState()
                this.renderEntity.startUsingItem(InteractionHand.MAIN_HAND)
                this.renderEntity.setHandActiveState()
            } else {
                this.renderEntity.setHeldItemOverride(ItemStack.EMPTY)
                this.renderEntity.clearHandActiveState()
            }

            this.renderVanillaItem(bowStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Threesome render pass: instead of the normal recursion, only the
     * `body2` bone is drawn normally and the `steve` bone is drawn
     * with the girl's render-scale factor and the player skin.
     */
    override fun renderModelBuffer(model: BakedGeoModel, entity: ManglelieEntity, r: Float, g: Float, b: Float, a: Float, scale: Float) {
        if (!ManglelieNpcModel.isInThreesome(entity) || model.topLevelBones().isEmpty()) {
            super.renderModelBuffer(model, entity, r, g, b, a, scale)
        } else {
            val topBone = model.topLevelBones()[0]
            var body2Bone: GeoBone? = null
            var steveBone: GeoBone? = null

            for (bone in topBone.childBones) {
                when (bone.name) {
                    "steve" -> steveBone = bone
                    "body2" -> body2Bone = bone
                }
            }

            // A reskinned/custom geo model may not carry the threesome bones at all.
            // Rendering a null bone NPEs on bone.getName(), so fall back to the
            // normal pass instead of crashing the render thread.
            if (body2Bone == null || steveBone == null) {
                super.renderModelBuffer(model, entity, r, g, b, a, scale)
                return
            }

            val poseStack = this.renderCtx.poseStack
            poseStack.pushPose()
            try {
                this.prepBoneMatrix(poseStack, topBone)
                this.renderRecursively(body2Bone, r, g, b, a)
                // steve is the interaction player's body (the shared threesome animations
                // pose it), so it needs the player skin - binding the girl's own texture
                // here gave the player a garbled girl skin.
                this.renderCtx.switchTexture(this.getSkinTexture(this.renderEntity))
                this.renderRecursively(steveBone, r, g, b, this.renderEntity.getRenderScaleFactor())
            } finally {
                this.renderCtx.resetTexture()
                poseStack.popPose()
            }
        }
    }

    /**
     * Parent-chain rule for the corruption cloth bones: bones under
     * `clothBoob*` are always renderable (they are part of the custom
     * mesh), everything else follows [IGirlRenderer.hasParentBone].
     */
    override fun hasParentBone(blacklistedBones: HashSet<String>, bone: GeoBone): Boolean {
        var current = bone
        while (current.parent != null) {
            val boneName = current.name
            if (boneName.contains("clothBoob")) {
                return true
            }

            if (blacklistedBones.contains(boneName)) {
                return false
            }

            if (boneName.startsWith("armor")) {
                return false
            }

            current = current.parent
        }

        return true
    }

    /**
     * Render-position override: while running, pins the girl's yaw to her
     * movement yaw; while corrupting (not in threesome), aims her at the Galath
     * partner and returns the partner-relative look position (the corruption
     * pose renders at Galath's location).
     */
    fun getBoneWorldPosManglelie(manglelie: ManglelieEntity, partialTicks: Float, pos: Vec3): Vec3 {
        if (manglelie.getCurrentAction() == Action.RUN) {
            val yaw = manglelie.getYawRotation()
            manglelie.setYRot(yaw)
            manglelie.yBodyRotO = yaw
            manglelie.yBodyRot = yaw
            manglelie.yHeadRotO = yaw
            manglelie.yHeadRot = yaw
            return pos
        }

        if (isCorrupting(manglelie)) {
            val galath = manglelie.getGalathPartner(false)
            if (galath != null) {
                renderGalathInteract(galath, partialTicks, manglelie)
                return getLookVector(galath, partialTicks)
            }
        }

        return pos
    }

    override fun getBoneWorldPos(entity: ManglelieEntity, partialTicks: Float, pos: Vec3): Vec3 =
        this.getBoneWorldPosManglelie(entity, partialTicks, super.getBoneWorldPos(entity, partialTicks, pos))

    companion object {
        @JvmField
        val CORRUPTION_COLOR_MAIN: UnknownScreen = UnknownScreen(115, 108, 188, 255)

        @JvmField
        val OFFSET_BODY: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.05f, 0.04f, 0.0f)

        @JvmField
        val OFFSET_ARM: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.065f, 0.0f)

        @JvmField
        val OFFSET_LEG: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.03f, 0.03f)

        @JvmField
        val CORRUPTION_COLOR_DARK: UnknownScreen = UnknownScreen(63, 59, 150, 255)

        @JvmField
        val CORRUPTION_COLOR_LIGHT: UnknownScreen = UnknownScreen(79, 74, 188, 255)

        @JvmField
        val BLACKLISTED_BONES: HashSet<String> = hashSetOf("boobs2", "booty2", "vagina2", "fuckhole2")

        /**
         * Whether the girl is close enough to her Galath partner for the
         * look/interact pose (partner's proximity value below `threshold`).
         */
        @JvmStatic
        fun isManglelieLooking(girl: BaseGirlEntity, threshold: Float): Boolean {
            if (girl !is ManglelieEntity) {
                return false
            }

            val galath = girl.getGalathPartner(false)
            return if (galath == null) false else galath.bodyScaleY < threshold
        }

        /**
         * First-person POV pass: draws the Galath geometry and the corruption
         * ribbon at the girl's position (or a small offset for locally registered
         * previews), then the wing mesh. Only when the girl is NOT in the
         * close-look pose and the local player exists. The pose must be at the
         * 1.12 render origin.
         */
        @JvmStatic
        fun renderMangleliePov(girl: BaseGirlEntity, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource) {
            val mc = Minecraft.getInstance()
            if (mc.player != null) {
                if (!isManglelieLooking(girl, 0.5f)) {
                    poseStack.pushPose()
                    try {
                        if (girl.isLocallyRegistered()) {
                            poseStack.translate(0.0, 0.01, 0.0)
                        } else {
                            GalathGeometryRender.renderGalathGeometry(mc, girl, partialTicks, poseStack)
                            renderManglelieRibbon(girl, partialTicks, poseStack)
                        }

                        GalathRenderer.renderWingMesh(girl, poseStack, bufferSource)
                    } finally {
                        poseStack.popPose()
                    }
                }
            }
        }

        /**
         * Counter-rotates the corruption ribbon by Manglelie's interpolated yaw so
         * it stays screen-aligned while she corrupts (skipped in threesome mode).
         */
        @JvmStatic
        fun renderManglelieRibbon(girl: BaseGirlEntity, partialTicks: Float, poseStack: PoseStack) {
            if (girl is ManglelieEntity) {
                if (girl.isCorrupting()) {
                    if (!ManglelieNpcModel.isInThreesome(girl)) {
                        val galath = girl.getGalathPartner(false)
                        if (galath != null) {
                            poseStack.mulPose(Axis.YP.rotationDegrees(-RotationHelper.lerpFloat(girl.yBodyRotO, girl.yBodyRot, partialTicks.toDouble())))
                        }
                    }
                }
            }
        }

        /**
         * True while the girl (Galath or her Manglelie partner) is NOT in a
         * threesome action — i.e. the look/interact poses are active.
         */
        @JvmStatic
        fun isGalathLooking(girl: BaseGirlEntity): Boolean {
            val target: BaseGirlEntity? = if (girl is GalathEntity) girl.getMangleliePartner(false) else girl
            return if (target == null) false else !Action.isAnyAction(target, Action.THREESOME_SLOW, Action.THREESOME_FAST, Action.THREESOME_CUM)
        }

        /**
         * Builds the 40-strip skirt mesh (dark/light alternating colors) from the
         * girl's cached `skirt_<i>_<j>` bone offsets; only drawn while the
         * look pose is active.
         */
        @JvmStatic
        fun renderManglelieMesh(girl: BaseGirlEntity, poseStack: PoseStack, bufferSource: MultiBufferSource) {
            if (isGalathLooking(girl)) {
                for (i in 0 until 39) {
                    renderManglelieStrip(girl, poseStack, bufferSource, i, i + 1)
                }

                renderManglelieStrip(girl, poseStack, bufferSource, 39, 0)
            }
        }

        /**
         * Emits one 8-vertex double quad (outer + inner skirt face) between the two
         * strip indices, tinted with the corruption color for the strip parity.
         */
        @JvmStatic
        fun renderManglelieStrip(girl: BaseGirlEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, startStrip: Int, endStrip: Int) {
            val s0 = girl.getCachedBoneOffset("skirt_${startStrip}_0")
            val s1 = girl.getCachedBoneOffset("skirt_${startStrip}_1")
            val s2 = girl.getCachedBoneOffset("skirt_${startStrip}_2")
            val e0 = girl.getCachedBoneOffset("skirt_${endStrip}_0")
            val e1 = girl.getCachedBoneOffset("skirt_${endStrip}_1")
            val e2 = girl.getCachedBoneOffset("skirt_${endStrip}_2")
            val c = if (startStrip % 2 == 0) CORRUPTION_COLOR_LIGHT else CORRUPTION_COLOR_DARK
            val buffer = bufferSource.getBuffer(RenderType.debugQuads())
            val pose = poseStack.last().pose()
            for (p in arrayOf(s0, s1, e1, e0, s1, e1, e2, s2)) {
                buffer.addVertex(pose, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).setColor(c.red, c.green, c.blue, c.alpha)
            }
        }

        /**
         * Skirt-follow bone logic: skirt bones 17..35 track the cheek rotation
         * (raised positionY by the cheek's rotation), skirt bones 1..11 (suffix
         * "1") copy the leg rotationX into rotationX/positionY — so the skirt
         * segments follow Manglelie's legs/cheeks while animating. No-op when the
         * game is paused or the driver bone's rotation is negative.
         */
        @JvmStatic
        fun applyBoneTransform(girl: BaseGirlEntity, boneName: String, bone: GeoBone, isSecondary: Boolean) {
            if (boneName.contains("skirt_")) {
                val boneIndex = parseBoneIndex(boneName)
                if (ThreadNames.isBetween(boneIndex.toDouble(), 17.0, 35.0)) {
                    if (Minecraft.getInstance().isPaused) {
                        return
                    }

                    var driverBone = if (boneIndex < 26) "cheekL" else "cheekR"
                    if (isSecondary) {
                        driverBone = driverBone + "2"
                    }

                    val driver = girl.getAnimationProcessor()?.getBone(driverBone) ?: return
                    val driverRotation = TrigMath.toDegrees(driver.rotX)
                    if (driverRotation < 0.0f) {
                        return
                    }

                    bone.setPosY(bone.posY + driverRotation * 0.01f)
                }

                if (ThreadNames.isBetween(boneIndex.toDouble(), 1.0, 11.0)) {
                    if (!boneName.endsWith("1")) {
                        return
                    }

                    var legBone = if (boneIndex < 6) "legR" else "legL"
                    if (isSecondary) {
                        legBone = legBone + "2"
                    }

                    val leg = girl.getAnimationProcessor()?.getBone(legBone) ?: return
                    val legRotation = TrigMath.toDegrees(leg.rotX)
                    if (legRotation < 0.0f) {
                        return
                    }

                    bone.setRotX(TrigMath.wrapDegrees(legRotation))
                    bone.setPosY(TrigMath.wrapDegrees(legRotation * 0.03f))
                }
            }
        }

        @JvmStatic
        fun parseBoneIndex(boneName: String): Int {
            val firstUnderscore = boneName.indexOf('_')
            val secondUnderscore = boneName.indexOf('_', firstUnderscore + 1)
            if (firstUnderscore != -1 && secondUnderscore != -1) {
                val indexString = boneName.substring(firstUnderscore + 1, secondUnderscore)
                return try {
                    indexString.toInt()
                } catch (e: NumberFormatException) {
                    -1
                }
            } else {
                return -1
            }
        }

        /**
         * Renders the corruption ribbon body mesh (three cloth-body segments
         * between the cached cloth bones) in the main corruption color.
         */
        @JvmStatic
        fun renderManglelieRibbonMesh(girl: BaseGirlEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, partialTicks: Float) {
            val buffer = bufferSource.getBuffer(RenderType.debugQuads())
            val leftMesh = GalathGeometryRender.buildBodyMesh(girl, partialTicks, "clothBoobLconStart", "clothBoobLconEnd", OFFSET_BODY, OFFSET_ARM)
            val rightMesh = GalathGeometryRender.buildBodyMesh(girl, partialTicks, "clothBoobRconStart", "clothBoobRconEnd", OFFSET_BODY, OFFSET_ARM)
            val midMesh = GalathGeometryRender.buildBodyMesh(girl, partialTicks, "clothBoobMidconStart", "clothBoobMidconEnd", OFFSET_LEG, OFFSET_LEG)
            GalathGeometryRender.renderMesh(poseStack, buffer, leftMesh, CORRUPTION_COLOR_MAIN)
            GalathGeometryRender.renderMesh(poseStack, buffer, rightMesh, CORRUPTION_COLOR_MAIN)
            GalathGeometryRender.renderMesh(poseStack, buffer, midMesh, CORRUPTION_COLOR_MAIN)
        }

        /**
         * Points the girl's head/body yaw at Galath (anchored: aim yaw; else the
         * head yaw + aim), so the corruption look-pose faces her correctly.
         */
        @JvmStatic
        fun renderGalathInteract(galath: GalathEntity, partialTicks: Float, target: LivingEntity) {
            if (galath.isAnchored()) {
                var yaw = galath.getYawRotation()
                var prevYaw = galath.getYawRotation()
                val aimYaw2 = GalathEntity.getAimYaw(galath, partialTicks)
                if (aimYaw2 != null) {
                    yaw = aimYaw2
                    prevYaw = aimYaw2
                }

                target.setYRot(yaw)
                target.yBodyRotO = prevYaw
                target.yBodyRot = yaw
                target.yHeadRotO = prevYaw
                target.yHeadRot = yaw
            } else {
                var headYaw = galath.yHeadRot
                var prevHeadYaw = galath.yHeadRotO
                val aimYaw = GalathEntity.getAimYaw(galath, partialTicks)
                if (aimYaw != null) {
                    headYaw = aimYaw
                    prevHeadYaw = aimYaw
                }

                target.setYRot(headYaw)
                target.yBodyRotO = prevHeadYaw
                target.yBodyRot = headYaw
                target.yHeadRotO = prevHeadYaw
                target.yHeadRot = headYaw
            }
        }

        /**
         * Whether Manglelie is in her corruption state and NOT in a threesome (the
         * only state where the corruption render paths apply).
         */
        @JvmStatic
        fun isCorrupting(manglelie: ManglelieEntity): Boolean = manglelie.isCorrupting() && !ManglelieNpcModel.isInThreesome(manglelie)

        @JvmStatic
        fun getLookVector(galath: GalathEntity, partialTicks: Float): Vec3 =
            EntityLookVectorHelper.getAimVector(galath, Minecraft.getInstance().player, partialTicks).add(galath.getCachedBoneOffset("mangPos"))

        @JvmStatic
        fun getEntityLookVector(galath: GalathEntity, partialTicks: Float): Vec3 =
            EntityLookVectorHelper.getEntityLookVector(galath, partialTicks).add(galath.getCachedBoneOffset("mangPos"))
    }
}
