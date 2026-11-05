package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.api.LightingType
import com.trolmastercard.sexmod.client.gui.ClothingScreen
import com.trolmastercard.sexmod.client.model.GirlModelBase
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BodyParts
import com.trolmastercard.sexmod.entity.BoneType
import com.trolmastercard.sexmod.entity.SexSceneEntity
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadModelStringPacket
import com.trolmastercard.sexmod.reskin.ReskinManager
import com.trolmastercard.sexmod.util.GirlRegistry
import com.trolmastercard.sexmod.util.IBoneRotationSupplier
import com.trolmastercard.sexmod.util.MatrixStack
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.joml.Vector4f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.cache.`object`.GeoCube
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.renderer.GeoEntityRenderer

/**
 * Renders [SexSceneEntity] — the custom model parts (dildos, outfits,
 * scene props) attached to a girl's bones. Used in three places: the
 * [ClothingScreen] preview, the `ClothingScreen` customization
 * list previews, and — critically — [renderSexSceneEffects], which
 * renders the girl's active custom parts at scene render time.
 *
 * **Attachment.** A part model's root bone is replaced by the girl's bone
 * matrix stack for the mapped bone ([renderBoneEffect]: custom leg/arm
 * names map to the girl's vanilla bone names via [initBoneMaps]), so
 * the part follows the girl's animation. Item models additionally rotate with
 * `ClothingScreen.currentModelYaw`.
 *
 * **Sentinel angles.** The render partial-tick sentinels [ANGLE_1_87] and
 * [ANGLE_2_87] (1.876945 / 2.876945) select "render at world
 * position" (free-standing preview) instead of bone attachment; `isCustom`
 * flags the per-part scene-effect renders. Anchored girls render the part at
 * their target position with their yaw. Do not change these sentinels.
 *
 * **Lighting.** [ServerWhitelistManager.ModelData] may declare
 * SEXMOD lighting (fake shading from the entity look vector) or NONE
 * (GL lighting off); otherwise the part is tinted by the host girl's block
 * light level.
 *
 * CLIENT-side render thread only.
 *
 * Port notes: the part is drawn with GeckoLib's default `entityCutoutNoCull` render type
 * (1.12 disabled culling). The vertices go through `sceneEntity.matrixStack` (the
 * girl's bone matrix, `util.MatrixStack`) and then the pose. The texture is the model's
 * (`SexSceneModel.getTextureResource`); the 1.12 class also overrode `getEntityTexture`
 * with the kobold egg texture, kept as [getEntityTexture] but not used for binding.
 */
class SexSceneRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<SexSceneEntity>) :
    GeoEntityRenderer<SexSceneEntity>(ctx, model) {
    @JvmField
    var mc: Minecraft = Minecraft.getInstance()

    @JvmField
    var sceneEntity: SexSceneEntity? = null

    @JvmField
    var modelData: ServerWhitelistManager.ModelData? = null

    @JvmField
    var legBoneMap: HashMap<String, String> = HashMap()

    @JvmField
    var bodyBoneMap: HashMap<String, String> = HashMap()

    @JvmField
    var boneRotations: HashMap<String, IBoneRotationSupplier> = HashMap()

    @JvmField
    var colorScale: Vec3 = Vec3(1.0, 1.0, 1.0)

    @JvmField
    var lightingPos: Vec3? = null

    init {
        this.initBoneMaps()
    }

    fun getEntityTexture(sceneEntity: SexSceneEntity): ResourceLocation {
        return Main.rl("textures/entity/kobold/egg.png")
    }

    /**
     * Maps custom part bone names onto the girl's vanilla bone names
     * (customLegL->legL, customArmL->armL, top->upperBody, ...) and registers
     * the lower-arm rotation suppliers (the parts' forearm bends follow the
     * girl's arm angles).
     */
    fun initBoneMaps() {
        this.legBoneMap["customLegL"] = "legL"
        this.legBoneMap["customShinL"] = "shinL"
        this.legBoneMap["customLegR"] = "legR"
        this.legBoneMap["customShinR"] = "shinR"
        this.bodyBoneMap["top"] = "upperBody"
        this.bodyBoneMap["customArmL"] = "armL"
        this.bodyBoneMap["customLowerArmL"] = "lowerArmL"
        this.bodyBoneMap["customArmR"] = "armR"
        this.bodyBoneMap["customLowerArmR"] = "lowerArmR"
        this.boneRotations["lowerArmR"] = IBoneRotationSupplier { girl -> TrigMath.wrapDegrees(girl.getRightArmAngle()) }
        this.boneRotations["lowerArmL"] = IBoneRotationSupplier { girl -> TrigMath.wrapDegrees(girl.getLeftArmAngle()) }
    }

    /**
     * Whether the part model should be drawn at all: item models and disabled
     * models are skipped; when no server whitelist exists yet, the part is
     * removed from the girl's custom-part set (and the new set uploaded to the
     * server) so stale parts don't linger client-side.
     */
    fun shouldRenderItemModel(sceneEntity: SexSceneEntity): Boolean {
        val modelCode = sceneEntity.getModelCode() ?: ""
        if (sceneEntity.isItemModel) {
            return false
        }

        if (ServerWhitelistManager.isModelDisabled(modelCode)) {
            return false
        }

        if (ServerWhitelistManager.getCustomModelsKey() != null) {
            return true
        }

        val girlUuid = sceneEntity.getGirlIdFromCode() ?: return true
        val girl = BaseGirlEntity.getClientGirlEntity(girlUuid) ?: return true

        val customParts = girl.getCustomPartsSet()
        customParts.remove(modelCode)
        val encodedParts = BaseGirlEntity.encodeCustomParts(customParts)
        PacketHandler.networkWrapper.sendToServer(UploadModelStringPacket(encodedParts, girlUuid))
        return true
    }

    /** Clears the per-frame sentinel flag before the GeckoLib pass (kept for parity with 1.12 `shouldRender`). */
    fun isRenderAngle(yaw: Float): Boolean {
        if (yaw == 2.876945f) {
            return true
        } else if (yaw == 1.876945f) {
            return true
        } else if (isCustom) {
            isCustom = false
            return true
        } else {
            return false
        }
    }

    /**
     * Resolves the part's lighting mode from its [LightingType]:
     * SEXMOD lights the part from the entity's look vector (fake shading),
     * NONE disables GL lighting, DEFAULT keeps world lighting.
     *
     * Port note: 1.21 entity shaders always apply directional shading; only the SEXMOD
     * vertex tint is reproduced.
     */
    fun renderModelData(modelData: ServerWhitelistManager.ModelData?, sceneEntity: SexSceneEntity, partialTicks: Float) {
        if (modelData != null && modelData.getLightingType() != LightingType.DEFAULT) {
            this.lightingPos = if (modelData.getLightingType() == LightingType.SEXMOD) WorldUtils.getEntityLookVector(sceneEntity, partialTicks) else null
        } else {
            this.lightingPos = null
        }
    }

    override fun render(entity: SexSceneEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.doRenderScene(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    /**
     * Main dispatch: gated by [isRenderAngle] and the global rendering
     * switch. Bone-attached parts are rendered relative to the host girl (or
     * her owner player) with the anchored yaw rotation; free-standing parts
     * (sentinel angles) render at their world position. Tint = the host's
     * block light level (clamped 10..15).
     *
     * For the bone-attached path `poseStack` must be at the 1.12 origin (the lerped local
     * player), as set up by [renderSexSceneEffects].
     */
    fun doRenderScene(entity: SexSceneEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        if (this.isRenderAngle(partialTicks)) {
            if (!ServerWhitelistManager.isGlobalRenderingDisabled()) {
                if (!this.shouldRenderItemModel(entity)) {
                    entity.matrixStack = MatrixStack()
                    val modelData = ServerWhitelistManager.getModelDataForGirl(entity.getModelCode() ?: "")
                    this.sceneEntity = entity
                    this.modelData = modelData
                    this.renderModelData(modelData, entity, partialTicks)
                    if (partialTicks != 1.876945f && partialTicks != 2.876945f) {
                        val girlUuid = entity.getGirlIdFromCode()
                        if (girlUuid != null) {
                            val girl = BaseGirlEntity.getClientGirlEntity(girlUuid)
                            if (girl != null) {
                                if (modelData == null || modelData.isDisabled() || girl.getOutfitIndex() != 0) {
                                    val renderEntity: LivingEntity
                                    if (girl !is AbstractPlayerGirlEntity) {
                                        renderEntity = girl
                                    } else {
                                        val ownerUuid = girl.getOwnerUserUUID() ?: return

                                        val owner = entity.level().getPlayerByUUID(ownerUuid)
                                        renderEntity = owner ?: girl
                                    }

                                    val transformPos = girl.renderCustomModelTransform(entity, renderEntity, partialTicks)
                                    val lightPos = BlockPos(
                                        Math.floor(renderEntity.x).toInt(),
                                        Math.floor(renderEntity.y).toInt(),
                                        Math.floor(renderEntity.z).toInt(),
                                    )
                                    val lightLevel = renderEntity.level().getMaxLocalRawBrightness(lightPos)
                                    val whiteColor = Vec3(1.0, 1.0, 1.0)
                                    val lightScale = ThreadNames.clampFloat(lightLevel.toFloat(), 10.0f, 15.0f) / 15.0f
                                    this.colorScale = Vec3(whiteColor.x * lightScale, whiteColor.y * lightScale, whiteColor.z * lightScale)
                                    poseStack.pushPose()
                                    try {
                                        poseStack.translate(transformPos.x, transformPos.y, transformPos.z)
                                        if (girl.isAnchored()) {
                                            poseStack.mulPose(Axis.YP.rotationDegrees(girl.getYawRotation()))
                                        }

                                        super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
                                    } finally {
                                        poseStack.popPose()
                                    }
                                }
                            }
                        }
                    } else {
                        this.colorScale = Vec3(1.0, 1.0, 1.0)
                        super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
                    }
                }
            }
        }
    }

    /**
     * The host entity whose pose the part follows: the owning player for
     * player-girls, else the girl itself.
     */
    fun getRenderEntityLiving(sceneEntity: SexSceneEntity): LivingEntity? {
        val girl = this.getRenderGirl(sceneEntity) ?: return null

        val renderEntity: LivingEntity
        if (girl !is AbstractPlayerGirlEntity) {
            renderEntity = girl
        } else {
            val ownerUuid = girl.getOwnerUserUUID()
            val owner = if (ownerUuid == null) null else sceneEntity.level().getPlayerByUUID(ownerUuid)
            renderEntity = owner ?: girl
        }

        return renderEntity
    }

    /**
     * The host girl for the part (registry lookup, fallback to the client girl
     * list).
     */
    fun getRenderGirl(sceneEntity: SexSceneEntity): BaseGirlEntity? {
        val girlUuid = sceneEntity.getGirlIdFromCode()
        val girl = if (girlUuid == null) null else GirlRegistry.getGirl(girlUuid)
        return girl ?: BaseGirlEntity.getClientGirlEntity(girlUuid)
    }

    fun renderBone(sceneEntity: SexSceneEntity, bone: GeoBone, partialTicks: Float) {
        val boneName = this.getBoneName(sceneEntity)
        if (boneName != null) {
            this.renderBoneEffect(sceneEntity, bone, partialTicks, boneName)
        }
    }

    /**
     * Attaches the part to the girl's bone: the part's root matrix is replaced
     * by the girl's bone matrix stack for the mapped bone name; item models
     * are scaled 0.5 and rotated by `ClothingScreen.currentModelYaw`.
     */
    fun renderBoneEffect(sceneEntity: SexSceneEntity, bone: GeoBone, partialTicks: Float, boneName: String) {
        val girl = this.getRenderGirl(sceneEntity)!! // java-npe: the part always belongs to a known girl
        this.getRenderEntityLiving(sceneEntity)
        sceneEntity.matrixStack = girl.getBoneMatrixStack(boneName, false)
        if (sceneEntity.isItemModel && partialTicks == 2.876945f) {
            sceneEntity.matrixStack.scale(0.5f, 0.5f, 0.5f)
            sceneEntity.matrixStack.rotateY(Math.toRadians(-ClothingScreen.currentModelYaw.toDouble()).toFloat())
        }
    }

    fun getBoneName(sceneEntity: SexSceneEntity): String? {
        if (sceneEntity.isItemModel) {
            return sceneEntity.boneType?.boneName
        } else {
            val modelData = ServerWhitelistManager.getModelDataForGirl(sceneEntity.getModelCode() ?: "") ?: return null
            return if (BoneType.CUSTOM_BONE == modelData.getBoneType()) modelData.getModelName() else modelData.getBoneType()?.boneName
        }
    }

    /**
     * GeckoLib bone recursion entry. For top-level bones this first runs the 1.12
     * `render(GeoModel, ...)` loop body (bone attachment unless the 1.876945 sentinel,
     * then the root pivot offset), then the matrix-stack recursion.
     */
    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: SexSceneEntity,
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
        val scene = this.sceneEntity ?: animatable
        this.sceneEntity = scene
        if (bone.parent == null) {
            if (partialTick != 1.876945f) {
                this.renderBone(scene, bone, partialTick)
            }

            scene.matrixStack.translate(-bone.pivotX / 16.0f, -bone.pivotY / 16.0f, -bone.pivotZ / 16.0f)
        }

        val c = software.bernie.geckolib.util.Color(colour)
        this.renderRecursively(poseStack, buffer, bone, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat, packedLight, packedOverlay)
    }

    /**
     * Bone recursion with the entity's matrix stack: transform push, cube
     * render, children (unless hidden), pop (with IllegalStateException guard).
     */
    fun renderRecursively(poseStack: PoseStack, buffer: VertexConsumer, bone: GeoBone, r: Float, g: Float, b: Float, a: Float, packedLight: Int, packedOverlay: Int) {
        val matrixStack = this.sceneEntity!!.matrixStack // java-npe: set by doRenderScene
        matrixStack.push()
        matrixStack.translate(bone)
        matrixStack.moveToPivot(bone)
        matrixStack.rotate(bone)
        matrixStack.scale(bone)
        matrixStack.moveBackFromPivot(bone)
        if (!bone.isHidden) {
            for (cube in bone.cubes) {
                matrixStack.push()
                this.renderCube(poseStack, buffer, cube, r, g, b, a, packedLight, packedOverlay)
                matrixStack.pop()
            }
        }

        if (!bone.isHidingChildren) {
            for (childBone in bone.childBones) {
                this.renderRecursively(poseStack, buffer, childBone, r, g, b, a, packedLight, packedOverlay)
            }
        }

        try {
            matrixStack.pop()
        } catch (e: IllegalStateException) {
            // ignored: popping an already-empty matrix stack is harmless
        } catch (e: java.util.NoSuchElementException) {
            // ignored: same, for deque-backed stacks
        }
    }

    /**
     * Cube pass for the part: transformed normals (mirrored on zero-size
     * faces), optional SEXMOD fake lighting applied to the tint, and vertices
     * emitted through the entity matrix stack (then the pose).
     */
    fun renderCube(poseStack: PoseStack, buffer: VertexConsumer, cube: GeoCube, r: Float, g: Float, b: Float, a: Float, packedLight: Int, packedOverlay: Int) {
        val matrixStack = this.sceneEntity!!.matrixStack // java-npe: set by doRenderScene
        matrixStack.moveToPivot(cube)
        matrixStack.rotate(cube)
        matrixStack.moveBackFromPivot(cube)
        val pose = poseStack.last()

        for (quad in cube.quads()) {
            if (quad == null) {
                continue
            }

            val normal = Vector3f(quad.normal())
            matrixStack.getNormalMatrix().transform(normal)
            val size = cube.size()
            if ((size.y == 0.0 || size.z == 0.0) && normal.x() < 0.0f) {
                normal.x *= -1.0f
            }

            if ((size.x == 0.0 || size.z == 0.0) && normal.y() < 0.0f) {
                normal.y *= -1.0f
            }

            if ((size.x == 0.0 || size.y == 0.0) && normal.z() < 0.0f) {
                normal.z *= -1.0f
            }

            val lighting = this.lightingPos
            if (lighting != null) {
                this.colorScale = BodyParts.offsetBonePosition(this.colorScale, normal, lighting)
            }

            val color = FastColor.ARGB32.colorFromFloat(
                Mth.clamp(a, 0.0f, 1.0f),
                Mth.clamp(this.colorScale.x.toFloat(), 0.0f, 1.0f),
                Mth.clamp(this.colorScale.y.toFloat(), 0.0f, 1.0f),
                Mth.clamp(this.colorScale.z.toFloat(), 0.0f, 1.0f),
            )
            val worldNormal = pose.normal().transform(Vector3f(normal))
            for (vertex in quad.vertices()) {
                val position = vertex.position()
                val matrixPos = Vector4f(position.x(), position.y(), position.z(), 1.0f)
                matrixStack.getModelMatrix().transform(matrixPos)
                pose.pose().transform(matrixPos)
                buffer.addVertex(
                    matrixPos.x(), matrixPos.y(), matrixPos.z(),
                    color,
                    vertex.texU(), vertex.texV(),
                    packedOverlay, packedLight,
                    worldNormal.x(), worldNormal.y(), worldNormal.z(),
                )
            }
        }
    }

    companion object {
        const val ANGLE_1_87: Float = 1.876945f
        const val ANGLE_2_87: Float = 2.876945f

        @JvmField
        var isCustom: Boolean = false

        /**
         * Static hook called from [GirlRenderer.renderFinal] after each
         * girl render: spawns a temporary [SexSceneEntity] per active custom
         * part and renders it at the origin (bone attachment happens via the
         * sentinel-angle path). The temporary entities must never be added to the
         * world — they exist only for the render call. CLIENT-side.
         *
         * `poseStack` must be at the 1.12 render origin (the lerped local player).
         */
        @JvmStatic
        fun renderSexSceneEffects(girl: BaseGirlEntity, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
            if (!girl.isRemoved) {
                if (girl.level().isClientSide) {
                    if (girl.hasCustomParts()) {
                        // A full-girl reskin replaces the base model; rendering legacy
                        // bone-attached parts on top would double-layer the textures
                        // (the "texture mashup"). Skip legacy parts for reskinned girls.
                        if (ReskinManager.INSTANCE.isGirlReskinned(girl)) {
                            return
                        }
                        val dispatcher = Minecraft.getInstance().entityRenderDispatcher

                        for (partCode in girl.getCustomPartsSet()) {
                            // CRASH GUARD: a legacy part whose model isn't registered
                            // (e.g. after a resource reload wiped the cache) would throw
                            // and kill the whole client. Skip any part whose ModelData /
                            // geo model is missing, and never let a single bad part take
                            // down the frame.
                            val md = ServerWhitelistManager.getModelDataForGirl(partCode)
                            if (md == null) {
                                logMissingLegacy(partCode, girl, "no ModelData")
                                clearStaleLegacy(girl, partCode)
                                continue
                            }
                            val res = md.getFallbackTexture()
                            if (res == null || !GirlModelBase.hasBakedModel(res)) {
                                logMissingLegacy(partCode, girl, if (res == null) "null geo resource" else "geo not in cache: $res")
                                clearStaleLegacy(girl, partCode)
                                continue
                            }
                            try {
                                val scenePart = SexSceneEntity(girl.level(), girl.getGirlId(), partCode)
                                isCustom = true
                                dispatcher.getRenderer(scenePart).render(scenePart, 0.0f, partialTicks, poseStack, bufferSource, packedLight)
                            } catch (t: Throwable) {
                                // never let a bad custom part crash rendering
                                if (t !is java.util.ConcurrentModificationException) {
                                    Main.LOGGER.warn(
                                        "[sexmod] skipped malformed custom part '" + partCode + "' on " + girl.displayName?.string +
                                            ": " + t.javaClass.simpleName + ": " + t.message,
                                    )
                                }
                                clearStaleLegacy(girl, partCode)
                            }
                        }
                    }
                }
            }
        }

        /** Logs a missing legacy part (one line, throttled-ish) so malformed/legacy models are auditable. */
        private fun logMissingLegacy(partCode: String, girl: BaseGirlEntity, why: String) {
            val girlName = girl.displayName?.string ?: "?"
            Main.LOGGER.warn("[sexmod] skipping missing legacy custom model '$partCode' on $girlName ($why)")
        }

        /** Removes a stale/broken legacy part code from the girl's custom set (client-side). */
        private fun clearStaleLegacy(girl: BaseGirlEntity, partCode: String) {
            try {
                val parts = girl.getCustomPartsSet()
                if (parts.isEmpty()) return
                if (!parts.remove(partCode)) return
                girl.setCustomModelCode(BaseGirlEntity.encodeCustomParts(parts))
            } catch (t: Throwable) {
                // best-effort cleanup
            }
        }

        /**
         * Computes the part's render position relative to the local player:
         * anchored girls pin the part to their target position + yaw (all pos/yaw
         * fields of the temp entity are overwritten — the entity code tolerates
         * this); otherwise the host entity's lerped position is used.
         */
        @JvmStatic
        fun getSceneEntityPosition(mc: Minecraft, sceneEntity: SexSceneEntity, host: LivingEntity, girl: BaseGirlEntity, partialTicks: Float): Vec3 {
            val result: Vec3
            if (girl.isAnchored()) {
                val targetPos = girl.getTargetPosition()
                val yaw = girl.getYawRotation()
                sceneEntity.xo = targetPos.x
                sceneEntity.yo = targetPos.y
                sceneEntity.zo = targetPos.z
                sceneEntity.xOld = targetPos.x
                sceneEntity.yOld = targetPos.y
                sceneEntity.zOld = targetPos.z
                sceneEntity.setPosRaw(targetPos.x, targetPos.y, targetPos.z)
                sceneEntity.setYRot(yaw)
                sceneEntity.yRotO = yaw
                sceneEntity.yHeadRot = yaw
                sceneEntity.yHeadRotO = yaw
                sceneEntity.yBodyRot = yaw
                sceneEntity.yBodyRotO = yaw
                sceneEntity.setXRot(yaw)
                sceneEntity.xRotO = yaw
                result = targetPos
            } else {
                sceneEntity.setYRot(host.yRot)
                sceneEntity.yRotO = host.yRotO
                sceneEntity.yHeadRot = host.yHeadRot
                sceneEntity.yHeadRotO = host.yHeadRotO
                sceneEntity.yBodyRot = host.yBodyRot
                sceneEntity.yBodyRotO = host.yBodyRotO
                sceneEntity.setXRot(host.xRot)
                sceneEntity.xRotO = host.xRotO
                sceneEntity.xo = host.xo
                sceneEntity.yo = host.yo
                sceneEntity.zo = host.zo
                sceneEntity.xOld = host.xOld
                sceneEntity.yOld = host.yOld
                sceneEntity.zOld = host.zOld
                sceneEntity.setPosRaw(host.x, host.y, host.z)
                result = RotationHelper.lerpVec3dDouble(Vec3(host.xOld, host.yOld, host.zOld), host.position(), partialTicks.toDouble())
            }

            val localPlayer = mc.player!! // java-npe: only called while rendering in a world
            val localPlayerPos = RotationHelper.lerpVec3dDouble(Vec3(localPlayer.xOld, localPlayer.yOld, localPlayer.zOld), localPlayer.position(), partialTicks.toDouble())
            return result.subtract(localPlayerPos)
        }
    }
}
