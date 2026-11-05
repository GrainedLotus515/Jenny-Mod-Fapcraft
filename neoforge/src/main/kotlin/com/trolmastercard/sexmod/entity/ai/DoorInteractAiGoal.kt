package com.trolmastercard.sexmod.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.block.DoorBlock

/**
 * <b>Role.</b> Door interaction for girl NPCs: scans for a wooden door within
 * 3 blocks ahead on the current path, opens it when close enough, walks
 * through and closes it behind. Requires a [GroundPathNavigation]
 * navigator (doors are ground-only).
 *
 * <b>Pitfalls.</b> The constructor throws for non-ground navigators — only
 * add this goal to ground-walking girls. The close happens when the entity
 * passes the door plane (`approachX * dx + approachZ * dz < 0`).
 *
 * Port notes: 1.12 `state.getMaterial() == Material.WOOD` is
 * [DoorBlock.isWoodenDoor]; `BlockDoor.toggleDoor` is [DoorBlock.setOpen];
 * `getEnterDoors()` is [GroundPathNavigation.canPassDoors]. The goal ticks
 * every game tick like 1.12's `updateTask`.
 */
open class DoorInteractAiGoal(@JvmField var entity: Mob) : Goal() {
    @JvmField
    var doorPosition: BlockPos = BlockPos.ZERO

    @JvmField
    var doorBlock: DoorBlock? = null

    @JvmField
    var hasOpenedDoor: Boolean = false

    @JvmField
    var approachX: Float = 0f

    @JvmField
    var approachZ: Float = 0f

    @JvmField
    var ticksRemaining: Int = 10

    init {
        if (entity.getNavigation() !is GroundPathNavigation) {
            throw IllegalArgumentException("Unsupported mob type for DoorInteractGoal")
        }
    }

    override fun canUse(): Boolean {
        var found = true

        for (x in -3 until 5) {
            for (z in -3 until 5) {
                val state = this.entity.level().getBlockState(this.entity.blockPosition().offset(x, 0, z))
                if (state.block is DoorBlock && DoorBlock.isWoodenDoor(state)) {
                    found = false
                    break
                }
            }

            if (!found) {
                break
            }
        }

        if (found) {
            return false
        }

        val navigator = this.entity.getNavigation() as GroundPathNavigation
        val path = navigator.path
        if (path != null && !path.isDone && navigator.canPassDoors()) {
            for (i in 0 until Math.min(path.nextNodeIndex + 2, path.nodeCount)) {
                val point = path.getNode(i)
                this.doorPosition = BlockPos(point.x, point.y + 1, point.z)
                if (this.entity.distanceToSqr(this.doorPosition.x.toDouble(), this.entity.y, this.doorPosition.z.toDouble()) <= 2.25) {
                    this.doorBlock = this.findDoorBlock(this.doorPosition)
                    if (this.doorBlock != null) {
                        return true
                    }
                }
            }

            this.doorPosition = this.entity.blockPosition().above()
            this.doorBlock = this.findDoorBlock(this.doorPosition)
            return this.doorBlock != null
        } else {
            return false
        }
    }

    override fun canContinueToUse(): Boolean = this.ticksRemaining >= 0

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun start() {
        this.hasOpenedDoor = false
        this.approachX = (this.doorPosition.x + 0.5f - this.entity.x).toFloat()
        this.approachZ = (this.doorPosition.z + 0.5f - this.entity.z).toFloat()
        this.toggleDoor(true)
    }

    override fun tick() {
        val dx = (this.doorPosition.x + 0.5f - this.entity.x).toFloat()
        val dz = (this.doorPosition.z + 0.5f - this.entity.z).toFloat()
        val dot = this.approachX * dx + this.approachZ * dz
        if (dot < 0.0f && --this.ticksRemaining <= 0) {
            this.toggleDoor(false)
            this.hasOpenedDoor = true
        }
    }

    override fun stop() {
        this.ticksRemaining = 10
    }

    /** 1.12 `BlockDoor.toggleDoor(world, pos, open)`. */
    private fun toggleDoor(open: Boolean) {
        val door = this.doorBlock ?: return
        val level = this.entity.level()
        val state = level.getBlockState(this.doorPosition)
        if (state.block === door) {
            door.setOpen(this.entity, level, state, this.doorPosition, open)
        }
    }

    private fun findDoorBlock(pos: BlockPos): DoorBlock? {
        val state = this.entity.level().getBlockState(pos)
        val block = state.block
        return if (block is DoorBlock && DoorBlock.isWoodenDoor(state)) block else null
    }
}
