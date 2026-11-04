package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.api.SkinColor
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.entity.GoblinPlayerEntity
import com.trolmastercard.sexmod.util.EyeColor
import com.trolmastercard.sexmod.util.HairColor
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import java.util.Random
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.Vec3i
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector4f
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the goblin (and goblin-player) entity. The goblin has the most
 * complex pose logic of all girls: it can ride the owner's shoulder
 * (first-person camera bob), be picked up, be thrown at players, or be caught;
 * in every state the render position/yaw is computed from the owner player's
 * view and the goblin is drawn in the correct perspective.
 *
 * **First-person sentinel.** The yaw sentinel [SENTINEL_VALUE]
 * (-420.69F) marks renders issued for the first-person camera
 * ([renderEntityInFirstPerson]): pose selection keys on it
 * (shoulder-idle/pick-up detection in [doRenderGoblin]) and it skips
 * the shadow pass. Do not change this value.
 *
 * **Customization.** The goblin's model is assembled from the owner's
 * model-code parts: ear/hair/body bones are swapped or hidden per part index
 * ([onBoneProcessing]), bone colors come from the model code (eye,
 * skin, hair, crown), and the held item switches to the goblin's inventory
 * item while running/catching ([resolveHeldItemStack]).
 *
 * **Pitfall:** every pose branch skips rendering when the goblin is the
 * local player's own and would be drawn in first person — the pose is drawn
 * by the first-person camera path instead. Position interpolation uses
 * [RotationHelper.lerpVec3dDouble] (PROGRESS lerp — correct here).
 *
 * Port notes: [doRenderEntity] routes through [doRenderGoblin] (the 1.12 class defined it but
 * nothing called it). A sentinel render arrives with the pose stack at the 1.12 origin
 * ([vanillaRenderBase] = zero). The throw-pose matrix push is popped whenever it was pushed
 * (1.12 popped only in first person and leaked otherwise). [setFirstPersonCamera] and
 * [renderEntityInFirstPerson] take the pose/buffer state.
 */
class GoblinRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out AbstractNpcOnlyEntity>, shadowSize: Double) :
    GirlRendererBase<GoblinEntity>(ctx, model, shadowSize) {
    @JvmField var currentYawOffset: Float = 0.0f
    @JvmField var isShoulderIdle: Boolean = false
    @JvmField var isBeingPickedUp: Boolean = false
    @JvmField var lightLevel: Float = 0.0f

    /**
     * Resolves the goblin's skin texture: the owner's cached skin (interaction
     * player, else owner) from the static cache map, or a freshly tinted skin
     * when not cached. In the preload world or with no owner it uses the local
     * player's profile id.
     */
    fun getGoblinTexture(goblin: GoblinEntity): ResourceLocation {
        var uuid = goblin.getInteractionPlayerUUID()
        if (uuid == null) {
            uuid = goblin.getOwnerUUID()
        }

        val cachedTexture: ResourceLocation?
        if (!SexWorldClient.isPreloadWorld(goblin.level()) && uuid != null) {
            cachedTexture = SKIN_TEXTURE_CACHE[uuid]
            if (cachedTexture == null) {
                return this.getTintedSkinTexture(uuid, goblin.level())
            }
        } else {
            cachedTexture = SKIN_TEXTURE_CACHE[ClientHooks.sessionProfileId()]
            if (cachedTexture == null) {
                return this.getTintedSkinTexture(ClientHooks.sessionProfileId(), goblin.level())
            }
        }

        return cachedTexture
    }

    override fun renderModel(model: BakedGeoModel, entity: GoblinEntity, partialTicks: Float, r: Float, g: Float, b: Float, a: Float) {
        super.renderModel(model, entity, partialTicks, r, g, b, entity.fadeScale)
    }

    override fun vanillaRenderBase(entity: GoblinEntity, entityYaw: Float, partialTick: Float): Vec3 {
        return if (entityYaw == SENTINEL_VALUE) Vec3.ZERO else super.vanillaRenderBase(entity, entityYaw, partialTick)
    }

    override fun doRenderEntity(entity: GoblinEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        // 1.12 doRenderShadowAndFire skipped the shadow while picked up / on the shoulder
        this.shadowRadius = if (entity.getCurrentAction() != Action.PICK_UP && entity.getCurrentAction() != Action.SHOULDER_IDLE) 0.2f else 0.0f
        val base = this.vanillaRenderBase(entity, entityYaw, partialTick)
        this.renderCtx.poseStack = poseStack
        this.renderCtx.bufferSource = bufferSource
        this.renderCtx.packedLight = packedLight
        this.doRenderGoblin(entity, base.x, base.y, base.z, entityYaw, partialTick)
    }

    /**
     * Main pose dispatcher (CLIENT render thread): detects first-person
     * shoulder-idle/pick-up via the sentinel yaw, then routes by action —
     * thrown/starting (render at the owner's throw aim, skip own first-person
     * views), shoulder-idle (bob + FOV-relative offset, hide for the owner in
     * first person), pick-up (track the owner's head), otherwise default
     * render. Two symmetric branches cover owned vs unowned goblins — keep them
     * in sync. `renderCtx` must hold the pose/buffer state.
     */
    fun doRenderGoblin(goblin: GoblinEntity, x0: Double, y0: Double, z0: Double, entityYaw: Float, partialTicks: Float) {
        var x = x0
        var y = y0
        var z = z0
        val poseStack = this.renderCtx.poseStack
        val bufferSource = this.renderCtx.bufferSource ?: return
        val packedLight = this.renderCtx.packedLight
        val player = mc.player ?: return
        this.renderEntity = goblin
        this.isShoulderIdle = SENTINEL_VALUE == entityYaw && goblin.getCurrentAction() == Action.SHOULDER_IDLE
        this.isBeingPickedUp = SENTINEL_VALUE == entityYaw && goblin.getCurrentAction() == Action.PICK_UP
        this.lightLevel = goblin.level().getMaxLocalRawBrightness(goblin.blockPosition()).toFloat()
        this.currentYawOffset = partialTicks
        currentActionValue = entityYaw
        val action = goblin.getCurrentAction()
        val ownerUuid = goblin.getOwnerUUID()
        var pushed = false
        if (ownerUuid != null) {
            if (goblin.isLocallyRegistered()) {
                val throwPos2 = getThrowPosition(goblin.level(), goblin, ownerUuid, x, y, z)
                x = throwPos2.x
                y = throwPos2.y
                z = throwPos2.z
            }

            if (action == Action.THROWN || action == Action.START_THROWING) {
                if (ClientHooks.thirdPersonView() == 0 && entityYaw == SENTINEL_VALUE && !goblin.isLocallyRegistered()) {
                    return
                }

                if (!goblin.isLocallyRegistered()) {
                    val yaw2 = goblin.getYawRotation()
                    goblin.yBodyRotO = yaw2
                    goblin.yBodyRot = yaw2
                }
            }

            if (isThrowAction(goblin, action)) {
                if (player.uuid == ownerUuid) {
                    if (SENTINEL_VALUE != entityYaw) {
                        return
                    }

                    goblin.yBodyRot = player.yRot + 180.0f
                    goblin.yBodyRotO = player.yRot + 180.0f
                    val lookVec2 = player.lookAngle
                    poseStack.pushPose()
                    pushed = true
                    poseStack.translate(lookVec2.x, lookVec2.y + player.eyeHeight, lookVec2.z)
                    val pitchVec2 = GoblinEntity.rotateVectorYaw(Vec3(-Math.abs(player.xRot).toDouble(), 0.0, 0.0), player.yRot)
                    rotateAroundAxis(poseStack, player.xRot, pitchVec2.x.toFloat(), 0.0f, pitchVec2.z.toFloat())
                    x = 0.0
                    y = 0.0
                    z = 0.0
                } else {
                    if (!goblin.isLocallyRegistered() || player.uuid == ownerUuid) {
                        if (player.uuid != ownerUuid) {
                            val owner = goblin.level().getPlayerByUUID(ownerUuid)
                            if (owner != null) {
                                goblin.yBodyRot = owner.yRot
                                goblin.yBodyRotO = owner.yRot
                            }
                        } else {
                            goblin.yBodyRot = player.yRot
                            goblin.yBodyRotO = player.yRot
                        }
                    }

                    val throwAim2 = getThrowAim(goblin, goblin.getOwnerUUID(), partialTicks)
                    x = throwAim2.x
                    y = throwAim2.y
                    z = throwAim2.z
                }
            } else if (this.isShoulderIdle) {
                setFirstPersonCamera(poseStack, partialTicks)
                var shoulderOffset2 = Vec3(RotationHelper.lerp(-0.1f, 0.2f, mc.options.fov().get() / 110.0f).toDouble(), 0.0, 0.0)
                shoulderOffset2 = GoblinEntity.rotateVectorYaw(shoulderOffset2, player.yRot)
                x = shoulderOffset2.x
                y = shoulderOffset2.y
                z = shoulderOffset2.z
                goblin.yBodyRot = player.yRot
                goblin.yBodyRotO = player.yRotO
                if (player.isCrouching) {
                    y -= 0.075
                }
            } else if (action == Action.SHOULDER_IDLE) {
                if (player.uuid == ownerUuid && ClientHooks.thirdPersonView() == 0) {
                    return
                }

                val owner2 = goblin.level().getPlayerByUUID(ownerUuid) ?: return

                val firstPersonView = getFirstPersonView(owner2, partialTicks)
                x = firstPersonView.x.toDouble()
                y = firstPersonView.y.toDouble()
                z = firstPersonView.z.toDouble()
                goblin.yBodyRot = firstPersonView.w
                if (owner2.isCrouching) {
                    y -= 0.32
                }
            } else if (action == Action.PICK_UP) {
                val pickUpOwner = goblin.level().getPlayerByUUID(ownerUuid)
                if (pickUpOwner != null) {
                    goblin.yBodyRotO = pickUpOwner.yHeadRotO
                    goblin.yBodyRot = pickUpOwner.yHeadRot
                }
            }

            try {
                super.doRenderEntity(goblin, x, y, z, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
            } finally {
                if (pushed) {
                    poseStack.popPose()
                }
            }
        } else {
            if (goblin.isLocallyRegistered()) {
                val throwPos = getThrowPosition(goblin.level(), goblin, ownerUuid, x, y, z)
                x = throwPos.x
                y = throwPos.y
                z = throwPos.z
            }

            if (action == Action.THROWN || action == Action.START_THROWING) {
                if (ClientHooks.thirdPersonView() == 0 && entityYaw == SENTINEL_VALUE && !goblin.isLocallyRegistered()) {
                    return
                }

                if (!goblin.isLocallyRegistered()) {
                    val yaw = goblin.getYawRotation()
                    goblin.yBodyRotO = yaw
                    goblin.yBodyRot = yaw
                }
            }

            if (isThrowAction(goblin, action)) {
                // ownerUuid is null on this branch, so the local-owner case never matches
                goblin.yBodyRot = player.yRot
                goblin.yBodyRotO = player.yRot
                val throwAim = getThrowAim(goblin, goblin.getOwnerUUID(), partialTicks)
                x = throwAim.x
                y = throwAim.y
                z = throwAim.z
            } else if (this.isShoulderIdle) {
                setFirstPersonCamera(poseStack, partialTicks)
                var shoulderOffset = Vec3(RotationHelper.lerp(-0.1f, 0.2f, mc.options.fov().get() / 110.0f).toDouble(), 0.0, 0.0)
                shoulderOffset = GoblinEntity.rotateVectorYaw(shoulderOffset, player.yRot)
                x = shoulderOffset.x
                y = shoulderOffset.y
                z = shoulderOffset.z
                goblin.yBodyRot = player.yRot
                goblin.yBodyRotO = player.yRotO
                if (player.isCrouching) {
                    y -= 0.075
                }
            } else {
                if (action == Action.SHOULDER_IDLE) {
                    return
                }
            }

            super.doRenderEntity(goblin, x, y, z, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
        }
    }

    /**
     * Maps a bone name to its tint from the girl's model code (parts[0..9]):
     * eyes -> eye color, variant/boob/nude bones -> skin color, hair/lash ->
     * hair color, crown handled separately. White (no tint) for unknown bones
     * or short model codes.
     */
    override fun getBoneColor(boneName: String): Vec3i {
        val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(this.renderEntity)
        return if (modelCodeParts.size < 8) {
            DEFAULT_BONE_COLOR
        } else if (boneName.contains("band")) {
            DEFAULT_BONE_COLOR
        } else if (boneName.contains("eyeColor") || boneName.contains("eyeColor2")) {
            getEyeColor(modelCodeParts[8])
        } else if (boneName.contains("variant") || boneName.contains("boob")) {
            getSkinColor(modelCodeParts[7])
        } else if (boneName.contains("hair")) {
            getHairColor(modelCodeParts[6])
        } else if (NUDE_BONE_NAMES.contains(boneName)) {
            getSkinColor(modelCodeParts[7])
        } else {
            if (LASH_BONE_NAMES.contains(boneName)) getHairColor(modelCodeParts[6]) else DEFAULT_BONE_COLOR
        }
    }

    /**
     * Per-bone geometry tweaks from the model code (skipped in the
     * [SexWorldClient] preload world): ears/hair swap to the chosen part
     * models, the body pivot drops to -0.15 (plus shoulder-idle pose), leg/boob
     * bones lean with the strafe/forward rotations, and crown bones hide
     * depending on ownership state.
     */
    override fun onBoneProcessing(boneName: String, bone: GeoBone) {
        if (!SexWorldClient.isPreloadWorld(this.renderEntity.level())) {
            val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(this.renderEntity)
            if (modelCodeParts.size >= 8) {
                when (boneName) {
                    "earL" -> applyBoneParts(bone, modelCodeParts[0], modelCodeParts[1], modelCodeParts[3])
                    "earR" -> applyBoneParts(bone, modelCodeParts[0], modelCodeParts[2], modelCodeParts[4])
                    "hair" -> applyBonePart(bone, modelCodeParts[5])
                    "body" -> {
                        bone.setPivotY(-0.15f)
                        applyBoneState(this.renderEntity, bone)
                    }
                    "LegR" -> applyBoneRot(this.isShoulderIdle, bone, 25.0f, 25.0f)
                    "boobR" -> applyBoneRot(this.isShoulderIdle, bone, 30.0f, 30.0f)
                    "boobR1" -> applyBoneRot(this.isShoulderIdle, bone, 10.0f, 15.0f)
                    "boobR2" -> applyBoneRot(this.isShoulderIdle, bone, 5.0f, 3.0f)
                }

                if (boneName.contains("crown")) {
                    applyBoneColor(this.renderEntity, bone, modelCodeParts[9])
                }
            }
        }
    }

    /**
     * Darkens bone tints by the local light level while the goblin is on the
     * shoulder or being picked up (first-person poses ignore world lighting).
     */
    override fun tintBoneColor(color: Vec3i): Vec3i {
        if (!this.isShoulderIdle && !this.isBeingPickedUp) {
            return color
        }

        val lightScale = ThreadNames.clampFloat(this.lightLevel, 2.0f, 15.0f) / 15.0f
        return Vec3i(Mth.floor(color.x * lightScale), Mth.floor(color.y * lightScale), Mth.floor(color.z * lightScale))
    }

    /**
     * While running or catching, the goblin holds its own inventory item
     * (`GoblinEntity.STOLEN_ITEM`) instead of the rendered default stack.
     */
    override fun resolveHeldItemStack(stack: ItemStack?): ItemStack? {
        val action = this.renderEntity.getCurrentAction()
        return if (action != Action.RUN && action != Action.CATCH) stack else this.renderEntity.entityData.get(GoblinEntity.STOLEN_ITEM)
    }

    override fun getBlacklistedBones(): HashSet<String> {
        return hashSetOf("boobs", "booty", "vagina", "fuckhole", "preggy", "LegL", "LegR", "cheekR", "cheekL")
    }

    override fun getDefaultScale(): Float {
        return if (this.renderEntity.getCurrentAction() == Action.CATCH) 0.5f else 1.0f
    }

    override fun getItemRenderOffset(stack: ItemStack?): Vec3 {
        return if (stack == null) {
            Vec3.ZERO
        } else {
            if (stack.item !is BlockItem && stack.maxStackSize != 1) Vec3(180.0, 0.0, 0.0) else super.getItemRenderOffset(stack)
        }
    }

    /**
     * Skips cubes of hidden custom-part bones and hides all leg bones in the
     * shoulder-idle pose (they would clip into the player's view).
     */
    override fun renderCubeGeometry(cube: GeoCube, bone: GeoBone, r: Float, g: Float, b: Float, a: Float, textureVOffset: Double) {
        if (!this.isShoulderIdle || LEG_BONE_NAMES.contains(bone.name)) {
            if (!this.activeCustomPartBones.contains(bone.name)) {
                this.currentRenderingBone = bone
                super.renderCubeGeometry(cube, bone, r, g, b, a, textureVOffset)
            }
        }
    }

    companion object {
        @JvmField val DEFAULT_BONE_COLOR: Vec3i = Vec3i(255, 255, 255)
        const val SENTINEL_VALUE: Float = -420.69f
        const val RENDER_SCALE_B: Float = 3.0f
        @JvmField val MOVEMENT_DIR_VECTOR: Vec3 = Vec3(10.0, -20.0, -10.0)
        const val LERP_FACTOR: Float = 0.1f

        @JvmField
        val NUDE_BONE_NAMES: HashSet<String> = hashSetOf(
            "meatTorso", "meatCheekR", "meatCheekL", "meatFootR", "meatFootL", "meatShinR", "meatShinL", "meatLegL", "meatLegR",
            "nippleR", "nippleL", "preggy", "shoeL", "shoeR", "frontAndInside", "Lside", "Rside", "cheekR", "cheekL", "fuckhole",
            "head", "nose", "neck", "armL", "lowerArmL", "armR", "lowerArmR", "torso", "LegL", "LegR", "shinL", "shinR",
        )

        @JvmField
        val LASH_BONE_NAMES: HashSet<String> = hashSetOf("lashR", "lashL", "closedR", "closedL", "browL", "browR", "closedL", "closedL")

        @JvmField
        val LEG_BONE_NAMES: HashSet<String> = hashSetOf("meatLegR", "meatShinR", "meatFootR", "boobR", "boobR1", "boobR2")

        @JvmField var currentActionValue: Float = 0.0f
        @JvmField var lastPlayerYaw: Float = 0.0f
        @JvmField var lastPlayerPitch: Float = 0.0f
        @JvmField var prevStrafeRotation: Float = 0.0f
        @JvmField var prevForwardRotation: Float = 0.0f
        @JvmField var strafeRotation: Float = 0.0f
        @JvmField var forwardRotation: Float = 0.0f

        /** `GlStateManager.rotate(angle, x, y, z)` with a non-normalised axis (no-op for a zero axis). */
        private fun rotateAroundAxis(poseStack: PoseStack, degrees: Float, x: Float, y: Float, z: Float) {
            val length = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (length == 0.0f) {
                return
            }
            poseStack.mulPose(Quaternionf().rotationAxis(Math.toRadians(degrees.toDouble()).toFloat(), x / length, y / length, z / length))
        }

        /**
         * Renders the goblin at the 1.12 origin with the first-person sentinel yaw, so
         * [doRenderGoblin] treats it as a first-person pose (shoulder-idle /
         * pick-up) and applies the camera-relative transform. `poseStack` must be at the
         * 1.12 origin (the lerped local player). CLIENT render thread.
         */
        @JvmStatic
        fun renderEntityInFirstPerson(girl: BaseGirlEntity, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
            mc.entityRenderDispatcher.getRenderer(girl).render(girl, SENTINEL_VALUE, partialTicks, poseStack, bufferSource, packedLight)
        }

        /**
         * Applies the first-person camera bob (vanilla walk-bob formula) so the
         * goblin on the shoulder sways with the player's steps. CLIENT render
         * thread; must run before drawing the goblin.
         */
        @JvmStatic
        fun setFirstPersonCamera(poseStack: PoseStack, partialTicks: Float) {
            val view = mc.getCameraEntity()
            val local = mc.player ?: return
            if (view is Player) {
                val walkDelta = view.walkDist - view.walkDistO
                val walkedDistance = -(view.walkDist + walkDelta * partialTicks)
                val cameraYaw = view.oBob + (view.bob - view.oBob) * partialTicks
                val bobOffset = Mth.sin(walkedDistance * Math.PI.toFloat()) * cameraYaw * 0.5f
                poseStack.translate(
                    Math.cos(local.yRot * (Math.PI / 180.0)) * bobOffset,
                    Math.abs(Mth.cos(walkedDistance * Math.PI.toFloat()) * cameraYaw).toDouble(),
                    Math.sin(local.yRot * (Math.PI / 180.0)) * bobOffset,
                )
            }
        }

        /**
         * Converts the girl's world position into a player-relative throw position:
         * the owner's head yaw is copied onto the girl (aiming her at the throw
         * direction), the action is set to START_THROWING, and the result is the
         * owner's position minus the local player's. Returns the input unchanged
         * when any of world/owner/girl is missing.
         */
        @JvmStatic
        fun getThrowPosition(world: Level?, girl: BaseGirlEntity?, ownerUuid: UUID?, x: Double, y: Double, z: Double): Vec3 {
            if (world == null) {
                return Vec3(x, y, z)
            }

            if (ownerUuid == null) {
                return Vec3(x, y, z)
            }

            if (girl == null) {
                return Vec3(x, y, z)
            }

            val owner = world.getPlayerByUUID(ownerUuid) ?: return Vec3(x, y, z)

            val ownerPos = owner.position()
            val localPlayerPos = mc.player!!.position() // java-npe: rendering implies a local player
            girl.yBodyRotO = owner.yHeadRotO
            girl.yBodyRot = owner.yHeadRot
            girl.setCurrentAction(Action.START_THROWING)
            return ownerPos.subtract(localPlayerPos)
        }

        /**
         * Whether the action renders the goblin in a thrown/caught/pick-up pose.
         * Start-throwing is only a render action for locally registered goblins,
         * and throw poses are never rendered in first-person view.
         */
        @JvmStatic
        fun isThrowAction(girl: BaseGirlEntity, action: Action): Boolean {
            if (action == Action.START_THROWING && !girl.isLocallyRegistered()) {
                return false
            }

            return if (ClientHooks.thirdPersonView() == 0 || action != Action.START_THROWING && action != Action.PICK_UP) {
                when (action) {
                    Action.PICK_UP, Action.CATCH, Action.CATCH_BJ, Action.CATCH_BJ_IDLE, Action.START_THROWING -> true
                    else -> false
                }
            } else {
                false
            }
        }

        /**
         * Lerped vector from the local player to the goblin's owner — the render
         * offset for throw/catch poses (goblin flies between the two players).
         */
        @JvmStatic
        fun getThrowAim(girl: BaseGirlEntity, ownerUuid: UUID?, partialTicks: Float): Vec3 {
            if (ownerUuid == null) {
                return Vec3.ZERO
            }

            val owner = girl.level().getPlayerByUUID(ownerUuid) ?: return Vec3.ZERO
            val local = mc.player ?: return Vec3.ZERO

            val ownerPos = RotationHelper.lerpVec3dDouble(Vec3(owner.xo, owner.yo, owner.zo), owner.position(), partialTicks.toDouble())
            val localPlayerPos = RotationHelper.lerpVec3dDouble(Vec3(local.xo, local.yo, local.zo), local.position(), partialTicks.toDouble())
            return ownerPos.subtract(localPlayerPos)
        }

        /**
         * First-person shoulder camera: lerped position of the owner relative to
         * the local player plus the owner's interpolated render yaw (as .w).
         */
        @JvmStatic
        fun getFirstPersonView(owner: Player, partialTicks: Float): Vector4f {
            val localPlayer = mc.player!! // java-npe: rendering implies a local player
            val renderYaw = RotationHelper.lerp(owner.yBodyRotO, owner.yBodyRot, partialTicks)
            val ownerPos = RotationHelper.lerpVec3dDouble(Vec3(owner.xOld, owner.yOld, owner.zOld), owner.position(), partialTicks.toDouble())
            val localPlayerPos = RotationHelper.lerpVec3dDouble(Vec3(localPlayer.xOld, localPlayer.yOld, localPlayer.zOld), localPlayer.position(), partialTicks.toDouble())
            val offset = ownerPos.subtract(localPlayerPos)
            return Vector4f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat(), renderYaw)
        }

        @JvmStatic
        fun getEyeColor(code: String): Vec3i = EyeColor.entries[Integer.parseInt(code)].getColor()

        @JvmStatic
        fun getSkinColor(code: String): Vec3i = SkinColor.entries[Integer.parseInt(code)].getColor()

        @JvmStatic
        fun getHairColor(code: String): Vec3i = HairColor.entries[Integer.parseInt(code)].getColor()

        /**
         * Hides the crown bone by ownership: hidden for locally registered girls,
         * hidden when the model-code flag is 0 for NPC goblins, hidden when the
         * player-goblin wears no helmet.
         */
        @JvmStatic
        fun applyBoneColor(girl: BaseGirlEntity, bone: GeoBone, crownCode: String) {
            if (girl.isLocallyRegistered()) {
                bone.setHidden(true)
            } else if (girl is GoblinEntity) {
                val crownVariant = Integer.parseInt(crownCode)
                bone.setHidden(crownVariant == 0)
            } else if (girl is GoblinPlayerEntity) {
                bone.setHidden(girl.entityData.get(AbstractGirlNpcEntity.HELMET_SLOT).isEmpty)
            }
        }

        /**
         * Leans the bone by the player's current strafe/forward rotation (clamped
         * to +/-the given angles) while the goblin rides the shoulder; no-op while
         * the game is paused.
         */
        @JvmStatic
        fun applyBoneRot(isShoulderIdle: Boolean, bone: GeoBone, maxForward: Float, maxStrafe: Float) {
            if (!mc.isPaused) {
                if (isShoulderIdle) {
                    bone.setRotX(bone.rotX + TrigMath.wrapDegrees(ThreadNames.clampFloat(forwardRotation, -maxForward, maxForward)))
                    bone.setRotZ(bone.rotZ + TrigMath.wrapDegrees(ThreadNames.clampFloat(strafeRotation, -maxStrafe, maxStrafe)))
                }
            }
        }

        /**
         * Body-bone pose for shoulder-idle: raises the pivot to 8 and tilts the
         * body with the camera pitch so the goblin "sits" in the player's view.
         */
        @JvmStatic
        fun applyBoneState(girl: BaseGirlEntity, bone: GeoBone) {
            if (currentActionValue == SENTINEL_VALUE && girl.getCurrentAction() == Action.SHOULDER_IDLE) {
                val cameraPitch = -mc.gameRenderer.mainCamera.xRot
                bone.setPivotY(8.0f)
                if (!mc.isPaused) {
                    bone.setRotX(bone.rotX + TrigMath.wrapDegrees(cameraPitch))
                }
            }
        }

        @JvmStatic
        fun applyBonePart(bone: GeoBone, partCode: String) {
            val partIndex = Integer.parseInt(partCode)
            GirlRendererBase.getChildBone(bone, partIndex)
        }

        @JvmStatic
        fun buildColorIndexGroups(groupCount: Int, code: String): HashSet<Int> {
            var groupIndex = Integer.parseInt(code)
            val lastIndex = groupCount - 1
            val groups = buildColorIndexGroups(lastIndex)

            while (groupIndex >= groups.size) {
                groupIndex -= groups.size
            }

            return groups[groupIndex]
        }

        @JvmStatic
        fun buildColorIndexGroups(size: Int): ArrayList<HashSet<Int>> {
            val groups = ArrayList<HashSet<Int>>()
            buildColorGroups(0, HashSet(), size, groups)
            return groups
        }

        @JvmStatic
        fun buildColorGroups(index: Int, current: HashSet<Int>, max: Int, groups: ArrayList<HashSet<Int>>) {
            if (index > max) {
                groups.add(current)
            } else {
                val next = HashSet(current)
                buildColorGroups(index + 1, current, max, groups)
                next.add(index)
                buildColorGroups(index + 1, next, max, groups)
            }
        }

        /**
         * Seeded pseudo-random color-group selection: index groups of the bone
         * variant space are deterministically chosen from the model-code seed
         * (squared percentage), so the same code always yields the same variant.
         */
        @JvmStatic
        fun parseColorGroup(boneCount: Int, code: String): HashSet<Int> {
            val chosen = HashSet<Int>()
            var seed = Integer.parseInt(code)
            seed = (0.01f * seed * seed).toInt()
            val groupCount = Math.round(seed / 100.0f * boneCount)
            val random = Random(seed.toLong())

            var i = 0
            while (i < groupCount) {
                val candidate = random.nextInt(boneCount)
                if (!chosen.contains(candidate)) {
                    chosen.add(candidate)
                } else {
                    i--
                }
                i++
            }

            return chosen
        }

        /**
         * Recursively selects a bone variant chain: hides all children of the
         * parent part bone and un-hides the chosen child index, so the model code
         * picks ear/hair variants.
         */
        @JvmStatic
        fun applyBoneParts(parentBone: GeoBone, partCode: String, childCode: String, colorCode: String) {
            // java-npe: an out-of-range part index threw in 1.12 as well
            val partBone = GirlRendererBase.getChildBone(parentBone, Integer.parseInt(partCode))!!
            val variantBone = GirlRendererBase.getChildBone(partBone, Integer.parseInt(childCode))!!
            val children = variantBone.childBones
            val childCount = children.size
            val colorGroup = parseColorGroup(childCount, colorCode)
            variantBone.childBones.forEach { it.setHidden(true) }
            colorGroup.forEach { GirlRendererBase.getChildBone(variantBone, it) }
        }
    }
}
