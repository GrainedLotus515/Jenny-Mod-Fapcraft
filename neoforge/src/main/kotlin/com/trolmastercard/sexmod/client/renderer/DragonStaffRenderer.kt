package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.model.DragonStaffModel
import com.trolmastercard.sexmod.client.model.GalathModel
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.item.DragonStaffItem
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.renderer.GeoItemRenderer

/**
 * Item renderer for the dragon staff: renders the geckolib staff model plus a
 * spinning end-crystal on the `staff` bone, with a bob animation, a
 * screen-space sway from the holder's movement, and an orbiting
 * colored-wool-particle ring driven by [KoboldEntity.tribeMembers] bone data.
 *
 * <b>Dual mode.</b> When [isRendering] (toggled from the
 * `StructureCommandScreen`) the particles are spread in the player's
 * view direction (ease-in-out toward the eye); otherwise they animate along
 * the bone chain. Wool colors encode particle ids.
 *
 * CLIENT-side render thread only. [renderRecursively] interrupts the
 * geckolib buffer before applying the custom bone transform — do not reorder
 * the push/draw/transform sequence.
 *
 * Port notes: 1.12 `render(item, stack)` -> [renderByItem] (stores the
 * pose stack/buffer source for the bone pass); the "flush the Tessellator and
 * rebind the texture" dance becomes switching render types on the buffer
 * source and re-acquiring the staff buffer for the remaining bones. Wool by
 * metadata becomes the 16 coloured wool items in `DyeColor` order.
 */
open class DragonStaffRenderer : GeoItemRenderer<DragonStaffItem>(DragonStaffModel()) {
    private val crystalModel: GalathModel = GalathModel()

    @JvmField
    var mc: Minecraft = Minecraft.getInstance()

    @JvmField
    var screenPos: Vector2f? = null

    @JvmField
    var animationTicks: Double = 0.0

    @JvmField
    var player: Player? = null

    @JvmField
    var heldItem: ItemStack? = null

    private var currentPartialTick: Float = 0.0f
    private var currentLight: Int = 0
    private var currentOverlay: Int = 0

    /**
     * Entry point: locates the player holding this exact staff stack (main or
     * off hand), computes his screen-space movement vector, advances the
     * animation clock (paused when the game is paused) and delegates to the
     * geckolib pipeline. Stores the holder/stack for the recursive pass.
     */
    override fun renderByItem(
        stack: ItemStack,
        transformType: ItemDisplayContext,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        this.renderStaffItem(stack)
        this.currentLight = packedLight
        this.currentOverlay = packedOverlay
        super.renderByItem(stack, transformType, poseStack, bufferSource, packedLight, packedOverlay)
    }

    fun renderStaffItem(stack: ItemStack) {
        var holder: Player? = null

        for (player in this.mc.level?.players() ?: emptyList()) {
            if (player.inventory.items.contains(stack)) {
                holder = player
                break
            }

            if (player.inventory.offhand.contains(stack)) {
                holder = player
                break
            }
        }

        if (holder != null) {
            val deltaX = holder.x - holder.xOld
            val deltaZ = holder.z - holder.zOld
            val yawRadians = (Math.PI / 180.0) * holder.yRot
            this.screenPos = Vector2f(
                (deltaX * Math.cos(yawRadians) + deltaZ * Math.sin(yawRadians)).toFloat(),
                (-deltaX * Math.sin(yawRadians) + deltaZ * Math.cos(yawRadians)).toFloat(),
            )
        } else {
            this.screenPos = Vector2f(0.0f, 0.0f)
        }

        this.currentPartialTick = this.mc.timer.getGameTimeDeltaPartialTick(true)
        if (!this.mc.isPaused) {
            this.animationTicks = (this.mc.player?.tickCount ?: 0) + this.currentPartialTick.toDouble()
        }

        this.heldItem = stack
        this.player = holder
    }

    /**
     * Custom pass for the `staff` bone: apply the bone transform, then draw the
     * bobbing crystal (rotation accumulates the holder's movement per frame in
     * the static map [swayByStack] keyed by ItemStack) and the orbiting wool
     * particles. Re-acquires the staff buffer for the remaining bones.
     */
    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: DragonStaffItem,
        bone: GeoBone,
        renderType: RenderType,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        var staffBuffer = buffer
        if ("staff" == bone.name) {
            poseStack.pushPose()
            try {
                MatrixHelper.applyBoneTransform(poseStack, bone)
                poseStack.translate(0.0, 1.5 + 0.001 * Math.sin(0.005 * this.animationTicks) + 0.001, 0.0)
                var storedSway = swayByStack[this.heldItem]
                val bob = this.getBobOffset().toFloat()
                poseStack.scale(bob, bob, bob)
                if (storedSway == null) {
                    storedSway = Vector3f(0.0f, 0.0f, 0.0f)
                }

                val holder = this.player
                val screen = this.screenPos ?: Vector2f(0.0f, 0.0f)
                storedSway.add(Vector3f(screen.x, if (holder == null) 0.0f else (holder.y - holder.yOld).toFloat(), screen.y))
                poseStack.mulPose(Axis.XP.rotationDegrees(storedSway.z * 10.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(storedSway.x * 10.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(-storedSway.y * 10.0f))
                poseStack.mulPose(rotationAround((this.animationTicks * 0.1f).toFloat(), 1.0f, 1.0f, 1.0f))
                val held = this.heldItem
                if (held != null) {
                    swayByStack[held] = storedSway
                }

                val crystalBuffer = bufferSource.getBuffer(RenderType.entityTranslucent(CRYSTAL_TEXTURE))
                this.crystalModel.render(poseStack, crystalBuffer, packedLight, packedOverlay, 0.0f)
            } finally {
                poseStack.popPose()
            }

            if (this.player != null) {
                this.collectAnimationBones(poseStack, bufferSource, packedLight, packedOverlay)
            }

            // 1.12 rebound the staff texture and restarted the buffer for the remaining bones
            staffBuffer = bufferSource.getBuffer(renderType)
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, staffBuffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }

    /**
     * Reads the particle chain from [KoboldEntity.tribeMembers] (id + position
     * tuples) and either spreads the particles toward the player's view
     * (rendering mode, [renderParticles]) or staggers them along the
     * bone order (animation mode, [animateBones]).
     */
    fun collectAnimationBones(poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        val particleIds = ArrayList<Int>()
        val particlePositions = ArrayList<Vec3>()

        for (particleData in KoboldEntity.tribeMembers) {
            particleIds.add(particleData.w.toInt())
            particlePositions.add(Vec3(particleData.x, particleData.y, particleData.z))
        }

        if (particleIds.size != 0) {
            if (isRendering) {
                this.renderParticles(particleIds, particlePositions, poseStack, bufferSource, packedLight, packedOverlay)
            } else {
                this.animateBones(particleIds, poseStack, bufferSource, packedLight, packedOverlay)
            }
        }
    }

    /**
     * Rendering-mode particle placement: for each chain particle, rotates its
     * offset into the holder's head-view space and eases each axis
     * ([easeInOut]) so the wool cubes stream toward the player's eye,
     * scaled by 1.3.
     */
    fun renderParticles(
        particleIds: List<Int>,
        particlePositions: List<Vec3>,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val holder = this.player ?: return
        val partialTicks = this.currentPartialTick
        for (i in particleIds.indices) {
            val headYaw = RotationHelper.lerp(holder.yHeadRotO, holder.yHeadRot, partialTicks)
            val headPitch = RotationHelper.lerp(holder.xRotO, holder.xRot, partialTicks)
            val eyePos = RotationHelper.lerpVec3dDouble(
                Vec3(holder.xo, holder.yo + holder.eyeHeight, holder.zo),
                holder.position().add(0.0, holder.eyeHeight.toDouble(), 0.0),
                partialTicks.toDouble(),
            )
            var relative = eyePos.subtract(particlePositions[i])
            relative = VectorMath.rotateByYawPitch(relative, -headPitch, headYaw)
            val magnitude = Math.abs(relative.x) + Math.abs(relative.z) + Math.abs(relative.y)
            var easedX = -relative.x / magnitude
            var easedY = -relative.y / magnitude
            var easedZ = relative.z / magnitude
            easedX = this.easeInOut(easedX)
            easedY = this.easeInOut(easedY)
            easedZ = this.easeInOut(easedZ)
            easedX *= 1.3f
            easedY *= 1.3f
            easedZ *= 1.3f
            this.renderParticleFrom(particleIds[i], easedX.toFloat(), easedY.toFloat(), easedZ.toFloat(), poseStack, bufferSource, packedLight, packedOverlay)
        }
    }

    /**
     * Animation-mode placement: staggers the chain particles evenly along a
     * spinning arc (rotation axis per particle, speed scaled by an index
     * lerp 0.8..1.2).
     */
    fun animateBones(particleIds: List<Int>, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        val step = 1.0f / particleIds.size
        var progress = 0.0f

        for (i in particleIds.indices) {
            progress += step
            this.renderParticleAt(
                particleIds[i], 1.0f - progress, 0.0f + progress,
                RotationHelper.lerpDouble(0.8f.toDouble(), 1.2f.toDouble(), i.toDouble() / particleIds.size).toFloat(),
                poseStack, bufferSource, packedLight, packedOverlay,
            )
        }
    }

    fun easeInOut(value: Double): Double = value * Math.sqrt(1.0 - value * value / 2.0)

    fun getBobOffset(): Double = 0.175f + 0.025 * Math.sin(0.005 * this.animationTicks) + 0.025

    fun renderParticleAt(colorId: Int, x: Float, y: Float, z: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        this.renderItem(woolStack(colorId), x, y, z, poseStack, bufferSource, packedLight, packedOverlay)
    }

    fun renderParticleFrom(colorId: Int, x: Float, y: Float, z: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        this.renderItemAt(woolStack(colorId), x, y, z, poseStack, bufferSource, packedLight, packedOverlay)
    }

    /**
     * Draws one wool-cube particle (color = particle id) translated by the
     * given offset from the crystal anchor. Tiny 0.04 scale, vanilla item
     * rendering.
     */
    fun renderItemAt(stack: ItemStack, x: Float, y: Float, z: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        poseStack.pushPose()
        try {
            poseStack.translate(0.0, 1.5 + 0.001 * Math.sin(0.005 * this.animationTicks) + 0.001, 0.0)
            poseStack.scale(0.04f, 0.04f, 0.04f)
            poseStack.translate(x * 6.0f, y * 6.0f, z * 6.0f)
            this.renderVanillaItem(stack, poseStack, bufferSource, packedLight, packedOverlay)
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Draws one wool-cube particle rotating around the given axis with speed
     * scaled by `speed`, offset by 6 units along X.
     */
    fun renderItem(stack: ItemStack, rotX: Float, rotY: Float, speed: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        poseStack.pushPose()
        try {
            poseStack.translate(0.0, 1.5 + 0.001 * Math.sin(0.005 * this.animationTicks) + 0.001, 0.0)
            poseStack.scale(0.04f, 0.04f, 0.04f)
            poseStack.mulPose(rotationAround((this.animationTicks * 8.0 * speed).toFloat(), 0.0f, rotX, rotY))
            poseStack.translate(6.0f, 0.0f, 0.0f)
            this.renderVanillaItem(stack, poseStack, bufferSource, packedLight, packedOverlay)
        } finally {
            poseStack.popPose()
        }
    }

    private fun renderVanillaItem(stack: ItemStack, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        val player = this.mc.player
        this.mc.itemRenderer.renderStatic(
            player, stack, ItemDisplayContext.NONE, false, poseStack, bufferSource, this.mc.level, packedLight, packedOverlay, player?.id ?: 0,
        )
    }

    companion object {
        private val CRYSTAL_TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png")
        const val SCALE_10: Float = 10.0f
        const val SCALE_1_5: Float = 1.5f
        const val SCALE_0_175: Float = 0.175f
        const val ROT_0_1: Float = 0.1f
        const val SPEED_0_04: Float = 0.04f
        const val SCALE_8: Float = 8.0f
        const val SCALE_6: Float = 6.0f
        const val SCALE_1_3: Float = 1.3f

        @JvmField
        var isRendering: Boolean = false

        @JvmField
        var swayByStack: HashMap<ItemStack, Vector3f> = HashMap()

        /** 1.12 wool metadata (EnumDyeColor order) -> 1.21 coloured wool item. */
        private val WOOL_BY_META = arrayOf(
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
            Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL,
        )

        private fun woolStack(colorId: Int): ItemStack = ItemStack(WOOL_BY_META[Math.floorMod(colorId, 16)], 1)

        /** `GlStateManager.rotate(angle, x, y, z)`: GL normalises the axis; a zero axis is a no-op. */
        private fun rotationAround(degrees: Float, x: Float, y: Float, z: Float): Quaternionf {
            val length = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            return if (length == 0.0f) {
                Quaternionf()
            } else {
                Quaternionf().rotationAxis(Math.toRadians(degrees.toDouble()).toFloat(), x / length, y / length, z / length)
            }
        }

        @JvmStatic
        fun isRenderingStaff(): Boolean = isRendering

        @JvmStatic
        fun toggleStaffRendering() {
            isRendering = !isRendering
        }
    }
}
