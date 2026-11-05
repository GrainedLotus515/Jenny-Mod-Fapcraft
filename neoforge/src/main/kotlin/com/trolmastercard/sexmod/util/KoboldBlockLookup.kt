package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.KoboldEntity
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The block side of the kobold tribe system for
 * [KoboldManager]: pairing a bed with its other half, finding the chest
 * half a chest was placed/broken against, testing whether a bed is already
 * assigned to a kobold, gathering the blocks a tribe owns (beds, chests, every
 * task's mining targets) and clearing stale saved positions.
 *
 * <b>Pitfalls.</b> The bed/chest scans check north, east, south and west in
 * order and keep the *last* match, so a bed touching several others pairs with
 * the western one. Block lookups run on the server thread only; the saved
 * position scan needs the position's area loaded and silently keeps positions
 * whose area is not loaded; positions whose kobold is gone are pruned.
 *
 * 1.21: the 1.12 `BlockChest.Type` (BASIC / TRAP) is the chest block itself
 * (`Blocks.CHEST` / `Blocks.TRAPPED_CHEST`), so `chestType` is a [Block].
 */
object KoboldBlockLookup {
    @JvmStatic
    fun isBedAssigned(beds: Map<KoboldEntity, Array<BlockPos>>, pos: BlockPos): Boolean {
        for (entry in beds.entries) {
            val bedPair = entry.value
            if (bedPair[0] == pos) {
                return true
            }

            if (bedPair[1] == pos) {
                return true
            }
        }

        return false
    }

    @JvmStatic
    fun findBedHead(world: Level, bedPos: BlockPos): BlockPos? {
        var headPos: BlockPos? = null
        if (world.getBlockState(bedPos.north()).block is BedBlock) {
            headPos = bedPos.north()
        }

        if (world.getBlockState(bedPos.east()).block is BedBlock) {
            headPos = bedPos.east()
        }

        if (world.getBlockState(bedPos.south()).block is BedBlock) {
            headPos = bedPos.south()
        }

        if (world.getBlockState(bedPos.west()).block is BedBlock) {
            headPos = bedPos.west()
        }

        return headPos
    }

    @JvmStatic
    fun findPairedChest(world: Level, pos: BlockPos, chestType: Block): BlockPos? {
        var pairedPos: BlockPos? = null
        if (isChestOfType(world, pos.north(), chestType)) {
            pairedPos = pos.north()
        }

        if (isChestOfType(world, pos.east(), chestType)) {
            pairedPos = pos.east()
        }

        if (isChestOfType(world, pos.south(), chestType)) {
            pairedPos = pos.south()
        }

        if (isChestOfType(world, pos.west(), chestType)) {
            pairedPos = pos.west()
        }

        return pairedPos
    }

    private fun isChestOfType(world: Level, pos: BlockPos, chestType: Block): Boolean {
        val block = world.getBlockState(pos).block
        return block is ChestBlock && chestType === block
    }

    @JvmStatic
    fun collectTribeBlocks(tasks: Collection<KoboldTask>, beds: Set<BlockPos>, chests: Set<BlockPos>): HashSet<BlockPos> {
        val blocks = HashSet<BlockPos>()

        for (task in tasks) {
            blocks.addAll(task.miningTargets)
        }

        blocks.addAll(beds)
        blocks.addAll(chests)
        return blocks
    }

    @JvmStatic
    fun scanSavedPositions(savedPositions: HashMap<UUID, BlockPos>, world: Level): HashMap<UUID, BlockPos> {
        val staleMemberIds = ArrayList<UUID>()

        for ((memberUuid, pos) in savedPositions) {
            if (world.isAreaLoaded(pos, 5)) {
                // 1.12 AxisAlignedBB(BlockPos, BlockPos): the two corners verbatim
                val aabb = AABB(Vec3.atLowerCornerOf(pos.subtract(Vec3i(3, 3, 3))), Vec3.atLowerCornerOf(pos.offset(3, 3, 3)))
                val koboldsNearby = world.getEntitiesOfClass(KoboldEntity::class.java, aabb)
                var hasKobold = false

                for (kobold in koboldsNearby) {
                    if (memberUuid == kobold.getGirlId()) {
                        hasKobold = true
                        break
                    }
                }

                if (!hasKobold) {
                    staleMemberIds.add(memberUuid)
                }
            }
        }

        if (staleMemberIds.isNotEmpty()) {
            savedPositions.keys.removeAll(staleMemberIds.toSet())
        }

        return savedPositions
    }
}
