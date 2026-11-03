package com.trolmastercard.sexmod.entity

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.server.level.ServerEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.Pose
import net.neoforged.neoforge.entity.PartEntity

/**
 * <b>Role.</b> A multi-part hitbox part for multi-part entities — used by
 * `GalathEntity` for its two energy-ball hitboxes. Collidable (pickable)
 * only while [isActive] is set, so the hitboxes exist exactly during the
 * summon charge window.
 *
 * Port notes: 1.12 `MultiPartEntityPart` is replaced by NeoForge's
 * [PartEntity]. Like the old part (and vanilla `EnderDragonPart`) it forwards
 * damage to its parent through the parent's `hurt`, is never saved and is
 * never sent to clients on its own. The parent must override
 * `isMultipartEntity()`, `getParts()` and `setId()` (see PORTING.md §2.4).
 * `MultiPartEntityPart.canBeCollidedWith()` (ray-trace hit test) maps to
 * [isPickable]. The 1.12 `IEntityMultiPart` parent contract is kept as the
 * nested [IEntityMultiPart] interface: when the parent implements it, damage
 * is routed to [IEntityMultiPart.attackEntityFromPart] exactly like 1.12,
 * otherwise to the parent's plain `hurt`. The 1.12 `SexEntityPart(World)`
 * constructor (null parent) is dropped: a [PartEntity] cannot exist without
 * a parent.
 */
open class SexEntityPart(parent: Entity, @JvmField val name: String, width: Float, height: Float) :
    PartEntity<Entity>(parent) {
    @JvmField
    var isActive: Boolean = false

    private val size: EntityDimensions = EntityDimensions.scalable(width, height)

    init {
        this.refreshDimensions()
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
    }

    override fun isPickable(): Boolean = this.isActive

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        // MultiPartEntityPart.attackEntityFrom -> parent.attackEntityFromPart(this, source, amount)
        if (this.isInvulnerableTo(source)) {
            return false
        }

        val owner = this.parent
        return if (owner is IEntityMultiPart) owner.attackEntityFromPart(this, source, amount) else owner.hurt(source, amount)
    }

    override fun `is`(entity: Entity): Boolean = this === entity || this.parent === entity

    override fun getAddEntityPacket(entity: ServerEntity): Packet<ClientGamePacketListener> {
        throw UnsupportedOperationException()
    }

    override fun getDimensions(pose: Pose): EntityDimensions = this.size

    override fun shouldBeSaved(): Boolean = false

    /** 1.12 `net.minecraft.entity.IEntityMultiPart`: the parent of [SexEntityPart]s. */
    interface IEntityMultiPart {
        fun attackEntityFromPart(part: SexEntityPart, source: DamageSource, amount: Float): Boolean
    }
}
