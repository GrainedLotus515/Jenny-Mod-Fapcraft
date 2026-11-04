package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.KoboldEntity
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level

/**
 * <b>Role.</b> A single tribe work order (MINE / FALL_TREE): target position,
 * facing, the set of blocks to break and the kobolds assigned as workers. Created
 * by `MinePacket`/`FallTreePacket` and executed by the kobold AI;
 * persisted by [KoboldManager.TribeWorldSavedData].
 *
 * <b>Capacity.</b> `TaskType` caps workers (FALL_TREE: 1, MINE: 3);
 * [addWorker] and [isFull] must agree on that cap.
 *
 * <b>Pitfalls.</b> [releaseWorkers] restores physics + anchor flags and
 * only skips kobolds that are themselves in an interaction. The static
 * [findConnectedBlocks] flood-fills logs both horizontally and one level
 * up and registers the FALL_TREE task itself — callers must not double-register.
 *
 * 1.21: the 1.12 `BlockLog` class check is the `#minecraft:logs` block tag.
 */
class KoboldTask {
    @JvmField
    var targetPos: BlockPos

    @JvmField
    var taskType: TaskType

    @JvmField
    var miningTargets: HashSet<BlockPos>

    @JvmField
    var workers: MutableList<KoboldEntity> = ArrayList()

    @JvmField
    var facing: Direction = Direction.NORTH

    constructor(targetPos: BlockPos, taskType: TaskType, miningTargets: HashSet<BlockPos>) {
        this.targetPos = targetPos
        this.taskType = taskType
        this.miningTargets = miningTargets
    }

    constructor(targetPos: BlockPos, taskType: TaskType, miningTargets: HashSet<BlockPos>, facing: Direction) {
        this.targetPos = targetPos
        this.taskType = taskType
        this.miningTargets = miningTargets
        this.facing = facing
    }

    fun getFacing(): Direction = this.facing

    fun getTargetPos(): BlockPos = this.targetPos

    fun getTaskType(): TaskType = this.taskType

    fun getMiningTargets(): HashSet<BlockPos> = this.miningTargets

    fun addMiningTarget(pos: BlockPos) {
        this.miningTargets.add(pos)
    }

    fun addMiningTargets(targets: HashSet<BlockPos>) {
        this.miningTargets.addAll(targets)
    }

    fun removeMiningTarget(pos: BlockPos) {
        this.miningTargets.remove(pos)
    }

    // removes the given targets; the misleading name is kept to avoid churn
    fun setMiningTargets(targets: HashSet<BlockPos>) {
        this.miningTargets.removeAll(targets)
    }

    fun isMiningTarget(pos: BlockPos): Boolean = this.miningTargets.contains(pos)

    fun addWorker(kobold: KoboldEntity): Boolean {
        if (this.taskType.targetPos <= this.workers.size) {
            return false
        }

        this.workers.add(kobold)
        return true
    }

    fun getWorkers(): MutableList<KoboldEntity> = this.workers

    fun releaseWorkers() {
        for (worker in this.workers) {
            if (worker.getInteractionPlayerUUID() == null) {
                worker.isNoGravity = false
                worker.noPhysics = false
                worker.setCurrentAction(Action.NULL)
                worker.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
            }
        }

        this.workers.clear()
    }

    fun removeWorker(kobold: KoboldEntity) {
        this.workers.remove(kobold)
    }

    fun isFull(): Boolean = this.taskType.targetPos <= this.workers.size

    fun hasWorker(kobold: KoboldEntity): Boolean = this.workers.contains(kobold)

    enum class TaskType(id: Int) {
        FALL_TREE(1),
        MINE(3);

        @JvmField
        var targetPos: Int = id
    }

    companion object {
        @JvmStatic
        fun findConnectedBlocks(world: Level, startPos: BlockPos, tribeUuid: UUID): HashSet<BlockPos> {
            var groundPos = startPos

            while (!isAboveMineable(world, groundPos)) {
                // jar-faithful intent: walk down to the ground; the original jar had
                // `startPos.down()` here (never advances) -> infinite loop on floating
                // logs / air below the tree
                groundPos = groundPos.below()
            }

            var topPos = startPos

            while (!isMineable(world, topPos)) {
                topPos = topPos.above()
            }

            val blocks = HashSet<BlockPos>()
            val height = topPos.y - groundPos.y

            for (i in 0..height) {
                blocks.add(groundPos.offset(0, i, 0))
            }

            val logs = findConnectedLogs(world, groundPos)
            val leafBlocks = HashSet<BlockPos>()

            for (log in logs) {
                if (log.x == groundPos.x && log.z == groundPos.z) {
                    leafBlocks.add(log)
                }
            }

            for (log2 in leafBlocks) {
                logs.remove(log2)
            }

            blocks.addAll(logs)
            val overlapping = HashSet<BlockPos>()

            for (block in blocks) {
                for (task in KoboldManager.getTribeTasks(tribeUuid)!!) { // java-npe
                    val taskTargets = task.getMiningTargets()
                    if (taskTargets.contains(block)) {
                        overlapping.add(block)
                        break
                    }
                }
            }

            blocks.removeAll(overlapping)
            val fallTask = KoboldTask(groundPos, TaskType.FALL_TREE, blocks)
            KoboldManager.addTask(tribeUuid, fallTask)
            return blocks
        }

        @JvmStatic
        fun isMineable(world: Level, pos: BlockPos): Boolean {
            val blockAbove = world.getBlockState(pos.above())
            return !blockAbove.`is`(BlockTags.LOGS)
        }

        @JvmStatic
        fun isAboveMineable(world: Level, pos: BlockPos): Boolean {
            val stateBelow = world.getBlockState(pos.below())
            // 1.12 compared the IBlockState itself against BlockLog (always false); kept as-is
            return !stateBelow.isAir
        }

        @JvmStatic
        fun findConnectedLogs(world: Level, pos: BlockPos): HashSet<BlockPos> = findMineableBlocks(world, pos, HashSet())

        @JvmStatic
        fun findMineableBlocks(world: Level, pos: BlockPos, visited: HashSet<BlockPos>): HashSet<BlockPos> {
            if (visited.contains(pos)) {
                return HashSet()
            }

            visited.add(pos)
            for (offset in NEIGHBOR_OFFSETS) {
                val next = pos.offset(offset[0], offset[1], offset[2])
                if (world.getBlockState(next).`is`(BlockTags.LOGS)) {
                    visited.addAll(findMineableBlocks(world, next, visited))
                }
            }

            return visited
        }

        /** The 17 neighbour offsets checked by [findMineableBlocks], in the Java order. */
        private val NEIGHBOR_OFFSETS: Array<IntArray> = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
            intArrayOf(1, 0, 1), intArrayOf(-1, 0, -1), intArrayOf(-1, 0, 1), intArrayOf(1, 0, -1),
            intArrayOf(0, 1, 0), intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0), intArrayOf(0, 1, 1),
            intArrayOf(0, 1, -1), intArrayOf(1, 1, 1), intArrayOf(-1, 1, -1), intArrayOf(-1, 1, 1),
            intArrayOf(1, 1, -1),
        )
    }
}
