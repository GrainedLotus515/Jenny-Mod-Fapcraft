package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.MatrixStack
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import software.bernie.geckolib.animatable.GeoEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.UUID

/**
 * **Role.** An invisible marker entity used as the render/placement anchor
 * for custom models: the scene-position reference ([modelCode] = girl
 * UUID, [modelData] = model DNA) and the item-preview mode
 * ([isItemModel] + [boneType]) for the inventory/wardrobe
 * screens. It is what [BaseGirlEntity.renderCustomModelTransform] and
 * the girl renderers use to position scene models.
 *
 * **State.** Synced keys `modelCode` and `modelData` (1.12 explicit ids 101/102),
 * both strings. Tiny (0.1 x 0.1 in 1.12), non-collidable, never pushed, and only
 * damageable by out-of-world damage. No controllers are registered
 * (geckolib is driven from the referenced girl instead).
 *
 * COMMON class (entity `sexmod:custom_model`). 1.21: `getRenderBoundingBox` ->
 * [getBoundingBoxForCulling], `isInRangeToRenderDist` -> [shouldRenderAtSqrDistance].
 */
open class SexSceneEntity : LivingEntity, GeoEntity {
    private val geoCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    @JvmField
    var isItemModel: Boolean = false

    @JvmField
    var matrixStack: MatrixStack = MatrixStack()

    @JvmField
    var boneType: BoneType? = null

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out SexSceneEntity>, level: Level) : super(type, level)

    constructor(level: Level) : this(ModEntities.CUSTOM_MODEL.get(), level)

    constructor(level: Level, uuid: UUID, modelDataStr: String) : this(level) {
        this.entityData.set(modelCode, uuid.toString())
        this.entityData.set(modelData, modelDataStr)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(modelCode, "")
        builder.define(modelData, "")
    }

    override fun getBoundingBoxForCulling(): AABB {
        val pos: BlockPos = this.blockPosition()
        return AABB(pos.x - 0.5, pos.y - 0.5, pos.z - 0.5, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    }

    override fun shouldRenderAtSqrDistance(dist: Double): Boolean = dist < 11000.0

    open fun getGirlIdFromCode(): UUID? {
        val code = this.entityData.get(modelCode)
        return if ("" == code) null else UUID.fromString(code)
    }

    override fun hurt(source: DamageSource, amount: Float): Boolean =
        if (!(source.`is`(DamageTypes.FELL_OUT_OF_WORLD) || source.`is`(DamageTypes.GENERIC_KILL))) false else super.hurt(source, amount)

    open fun getModelCode(): String? {
        val data = this.entityData.get(modelData)
        return if ("" == data) null else data
    }

    override fun isPushable(): Boolean = false

    override fun isPickable(): Boolean = false

    override fun die(source: DamageSource) {
        super.die(source)
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.geoCache

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
    }

    override fun getArmorSlots(): Iterable<ItemStack> = ArrayList()

    override fun getItemBySlot(slot: EquipmentSlot): ItemStack = ItemStack.EMPTY

    override fun setItemSlot(slot: EquipmentSlot, stack: ItemStack) {
    }

    override fun getMainArm(): HumanoidArm = HumanoidArm.LEFT

    companion object {
        const val DESPAWN_DISTANCE: Float = 11000.0f

        @JvmField
        val modelCode: EntityDataAccessor<String> = SynchedEntityData.defineId(SexSceneEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val modelData: EntityDataAccessor<String> = SynchedEntityData.defineId(SexSceneEntity::class.java, EntityDataSerializers.STRING)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = LivingEntity.createLivingAttributes()

        /**
         * Factory for the item-preview variant: links the scene entity to a girl
         * UUID and marks it as a model-bone preview (`isItemModel`,
         * `boneType`) so the renderer shows only the named bone.
         */
        @JvmStatic
        fun createSceneEntity(world: Level, uuid: UUID, boneType: BoneType?): SexSceneEntity {
            val scene = SexSceneEntity(world)
            scene.getEntityData().set(modelCode, uuid.toString())
            scene.isItemModel = true
            scene.boneType = boneType
            return scene
        }
    }
}
