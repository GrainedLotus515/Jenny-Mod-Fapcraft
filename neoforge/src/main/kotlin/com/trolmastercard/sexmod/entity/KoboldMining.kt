package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetControllerPacket
import com.trolmastercard.sexmod.networking.SendBlocksPacket
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.KoboldTask
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Mining and tree-felling behaviour for [KoboldEntity]: picking a block out
 * of the tribe task, walking to it, standing still long enough to start, then
 * destroying it (including the falling-log cascade and sapling replant).
 *
 * <b>State lives on the entity</b> (`miningTargetPos` target, `miningDropItem` replant item,
 * `mineCooldown` attempts, `cooldownTicks`, `taskTimer`, `miningCooldown`,
 * `miningStandTicks`) because the tribe task loop in `KoboldEntity`
 * reads `miningTargetPos` too. The methods here are stateless and take the entity as
 * their first argument.
 *
 * 1.21 flattening: `getItemDropped`/`damageDropped` is the first loot-table
 * drop ([Block.getDrops]); the 1.12 `log`/`log2` metadata → sapling metadata
 * table is a log-block → sapling-item table ([getBlockItem]); the replant
 * switch on sapling metadata 3 (jungle) / 5 (dark oak) tests those sapling
 * items; `BlockLog` is the `#minecraft:logs` tag; `EntityFallingBlock` is
 * [FallingBlockEntity.fall].
 */
object KoboldMining {
    /** 1.12 `new ItemStack(block.getItemDropped(state, rand, 0), 1, block.damageDropped(state))`. */
    private fun droppedItem(entity: KoboldEntity, pos: BlockPos, state: BlockState): ItemStack {
        val level = entity.level() as? ServerLevel ?: return ItemStack.EMPTY
        val drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos))
        return if (drops.isEmpty()) ItemStack.EMPTY else ItemStack(drops[0].item, 1)
    }

    /** 1.12 `IBlockState.isFullCube()`. */
    private fun isFullCube(entity: KoboldEntity, pos: BlockPos): Boolean =
        entity.level().getBlockState(pos).isCollisionShapeFullBlock(entity.level(), pos)

    /** 1.12 `IBlockState.isFullBlock()` (opaque full cube). */
    private fun isFullBlock(entity: KoboldEntity, pos: BlockPos): Boolean =
        entity.level().getBlockState(pos).isSolidRender(entity.level(), pos)

    @JvmStatic
    fun handleTaskState(entity: KoboldEntity, tribeId: UUID, task: KoboldTask) {
        if (entity.getCurrentAction() != Action.MINE) {
            handleTaskNavigation(entity, tribeId, task)
        } else {
            entity.taskTimer--
            entity.miningCooldown--
            if (entity.miningCooldown == 0) {
                val target = entity.miningTargetPos!! // java-npe
                val fallingState = entity.level().getBlockState(target.above())
                if (fallingState.block !is FallingBlock) {
                    task.removeMiningTarget(target)
                    val master = entity.getMasterPlayer()
                    if (master != null) {
                        PacketHandler.networkWrapper.sendTo(SendBlocksPacket(target, false), master as ServerPlayer)
                    }
                }

                val blockState = entity.level().getBlockState(target)
                entity.canExtractItem(droppedItem(entity, target, blockState))
                entity.level().destroyBlock(target, false)
            }

            if (entity.taskTimer <= 0) {
                entity.taskTimer = 100
                entity.miningCooldown = 24
                entity.setCurrentAction(Action.NULL)
            }
        }
    }

    @JvmStatic
    fun handleTaskNavigation(entity: KoboldEntity, tribeId: UUID, task: KoboldTask) {
        val navigator = entity.getNavigation()
        val target = entity.miningTargetPos
        if (target != null && task.getMiningTargets().contains(target)) {
            val blockState = entity.level().getBlockState(target)
            if (!entity.canInsertItem(droppedItem(entity, target, blockState))) {
                entity.tribeBlocked = true
                entity.canStoreInventory(tribeId, true)
            } else if (entity.deltaMovement.x == 0.0 &&
                entity.deltaMovement.z == 0.0 &&
                entity.onGround() &&
                !(Math.sqrt(entity.distanceToSqr(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())) > 3.0) &&
                ++entity.miningStandTicks >= 10
            ) {
                navigator.stop()
                entity.miningStandTicks = 0
                entity.setCurrentAction(Action.MINE)
                entity.yHeadRot = (Math.atan2(entity.z - target.z, entity.x - target.x) * (180.0 / Math.PI) + 90.0).toFloat()
                entity.yRot = entity.yHeadRot
                entity.getEntityData().set(KoboldEntity.MINING_WITH_AXE, false)
            } else {
                val standPos = target.offset(task.getFacing().opposite.normal)
                navigator.moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 0.35f.toDouble())
            }
        } else {
            val newTarget = executeMiningTask(entity, task, tribeId)
            entity.miningTargetPos = newTarget
            if (newTarget == null) {
                val noTargets = task.getMiningTargets().isEmpty()
                val blocks = KoboldManager.removeTaskAndGetBlocks(tribeId, task)
                val tribeUuid = KoboldManager.findTribeIdWith(tribeId)
                if (tribeUuid != null) {
                    val player = entity.level().getPlayerByUUID(tribeUuid)
                    if (player != null) {
                        if (!noTargets) {
                            player.sendSystemMessage(Component.literal(String.format("<%s> It's impossible to mine here...", entity.getDisplayNameText())))
                        }

                        PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, false), player as ServerPlayer)
                    }
                }
            } else {
                if (Math.abs(entity.blockPosition().y - task.getTargetPos().y) > 3) {
                    val targetPos = task.getTargetPos().offset(task.getFacing().opposite.normal)
                    entity.level().setBlockAndUpdate(targetPos, Blocks.AIR.defaultBlockState())
                    entity.syncTribeBlocks(targetPos)
                }

                val standPos = newTarget.offset(task.getFacing().opposite.normal)
                navigator.moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 0.35f.toDouble())
            }
        }
    }

    @JvmStatic
    fun executeMiningTask(entity: KoboldEntity, task: KoboldTask, tribeId: UUID): BlockPos? {
        val miningTargets = task.getMiningTargets()
        val facing = task.getFacing()
        val row = ArrayList<BlockPos>()
        var maxZ: Int? = null
        if (miningTargets.isEmpty()) {
            return null
        }

        for (target in miningTargets) {
            when (facing) {
                Direction.NORTH -> if (maxZ == null || target.z >= maxZ) {
                    maxZ = target.z
                    row.add(target)
                }
                Direction.SOUTH -> if (maxZ == null || target.z <= maxZ) {
                    maxZ = target.z
                    row.add(target)
                }
                Direction.EAST -> if (maxZ == null || target.x <= maxZ) {
                    maxZ = target.x
                    row.add(target)
                }
                Direction.WEST -> if (maxZ == null || target.x >= maxZ) {
                    maxZ = target.x
                    row.add(target)
                }
                else -> {}
            }
        }

        val column = ArrayList<BlockPos>()

        for (pos in row) {
            if ((facing == Direction.NORTH || facing == Direction.SOUTH) && pos.z == maxZ) {
                column.add(pos)
            }

            if ((facing == Direction.EAST || facing == Direction.WEST) && pos.x == maxZ) {
                column.add(pos)
            }
        }

        if (column.isEmpty()) {
            return null
        }

        val positions = ArrayList<BlockPos>()
        val taskFacing = task.getFacing()
        val targetPos = task.getTargetPos()
        var minePos: BlockPos
        if (taskFacing.axis == Direction.Axis.Z) {
            minePos = BlockPos(targetPos.x, targetPos.y, column[0].z)
            minePos = if (taskFacing == Direction.NORTH) minePos.north() else minePos.south()

            positions.add(minePos.below())
            positions.add(minePos.below().east())
            positions.add(minePos.below().west())
            positions.add(minePos)
            positions.add(minePos.above())
            positions.add(minePos.above().above())
            positions.add(minePos.above().above().above())
            positions.add(minePos.west())
            positions.add(minePos.west().above())
            positions.add(minePos.west().above().above())
            positions.add(minePos.west().above().above().above())
            positions.add(minePos.west().west())
            positions.add(minePos.west().west().above())
            positions.add(minePos.west().west().above().above())
            positions.add(minePos.east())
            positions.add(minePos.east().above())
            positions.add(minePos.east().above().above())
            positions.add(minePos.east().above().above().above())
            positions.add(minePos.east().east())
            positions.add(minePos.east().east().above())
            positions.add(minePos.east().east().above().above())
        } else {
            minePos = BlockPos(column[0].x, targetPos.y, targetPos.z)
            minePos = if (taskFacing == Direction.EAST) minePos.east() else minePos.west()

            positions.add(minePos.below())
            positions.add(minePos.below().north())
            positions.add(minePos.below().south())
            positions.add(minePos)
            positions.add(minePos.above())
            positions.add(minePos.above().above())
            positions.add(minePos.above().above().above())
            positions.add(minePos.south())
            positions.add(minePos.south().above())
            positions.add(minePos.south().above().above())
            positions.add(minePos.south().above().above().above())
            positions.add(minePos.south().south())
            positions.add(minePos.south().south().above())
            positions.add(minePos.south().south().above().above())
            positions.add(minePos.north())
            positions.add(minePos.north().above())
            positions.add(minePos.north().above().above())
            positions.add(minePos.north().above().above().above())
            positions.add(minePos.north().north())
            positions.add(minePos.north().north().above())
            positions.add(minePos.north().north().above().above())
        }

        val liquidBlocks = HashSet<BlockPos>()

        for (pos in positions) {
            if (entity.level().getBlockState(pos).liquid()) {
                entity.level().setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 2)
                if (column.contains(pos)) {
                    liquidBlocks.add(pos)
                }
            }
        }

        if (liquidBlocks.isNotEmpty()) {
            task.addMiningTargets(liquidBlocks)
            val master = entity.getMasterPlayer()
            if (master != null) {
                PacketHandler.networkWrapper.sendTo(SendBlocksPacket(liquidBlocks, true), master as ServerPlayer)
            }
        }

        positions.clear()
        positions.add(minePos.below())
        if (taskFacing.axis == Direction.Axis.Z) {
            positions.add(minePos.below().west())
            positions.add(minePos.below().east())
        } else {
            positions.add(minePos.below().north())
            positions.add(minePos.below().south())
        }

        for (pos in positions) {
            // 1.12 Block.isPassable == !material.blocksMovement()
            if (!entity.level().getBlockState(pos).blocksMotion()) {
                entity.level().setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState())
            }
        }

        val airBlocks = HashSet<BlockPos>()

        for (pos in column) {
            val block = entity.level().getBlockState(pos).block
            if (block === Blocks.AIR) {
                airBlocks.add(pos)
            }
        }

        if (airBlocks.isNotEmpty()) {
            column.removeAll(airBlocks)
            task.setMiningTargets(airBlocks)
            val tribeUuid = KoboldManager.findTribeIdWith(tribeId)
            if (tribeUuid != null) {
                val player = entity.level().getPlayerByUUID(tribeUuid)
                if (player != null) {
                    PacketHandler.networkWrapper.sendTo(SendBlocksPacket(airBlocks, false), player as ServerPlayer)
                }
            }
        }

        if (column.isEmpty()) {
            return executeMiningTask(entity, task, tribeId)
        }

        var workerMinePos: BlockPos? = null
        val workers = task.getWorkers()

        for (i in workers.indices) {
            if (workers[i].id == entity.id) {
                if (i == 0) {
                    workerMinePos = findTaskBlock(entity, column, -1, task.getFacing(), task.getTargetPos())
                    if (workerMinePos == null) {
                        workerMinePos = findTaskBlock(entity, column, 0, task.getFacing(), task.getTargetPos())
                        if (workerMinePos == null) {
                            workerMinePos = findTaskBlock(entity, column, 1, task.getFacing(), task.getTargetPos())
                        }
                    }
                    break
                }

                if (i == 1) {
                    workerMinePos = findTaskBlock(entity, column, 1, task.getFacing(), task.getTargetPos())
                    if (workerMinePos == null) {
                        workerMinePos = findTaskBlock(entity, column, 0, task.getFacing(), task.getTargetPos())
                        if (workerMinePos == null) {
                            workerMinePos = findTaskBlock(entity, column, -1, task.getFacing(), task.getTargetPos())
                        }
                    }
                    break
                }

                if (i == 2) {
                    workerMinePos = findTaskBlock(entity, column, 0, task.getFacing(), task.getTargetPos())
                    if (workerMinePos == null) {
                        workerMinePos = findTaskBlock(entity, column, 1, task.getFacing(), task.getTargetPos())
                        if (workerMinePos == null) {
                            workerMinePos = findTaskBlock(entity, column, -1, task.getFacing(), task.getTargetPos())
                        }
                    }
                    break
                }
            }
        }

        return workerMinePos
    }

    @JvmStatic
    fun findTaskBlock(entity: KoboldEntity, blocks: List<BlockPos>, index: Int, facing: Direction, targetPos: BlockPos): BlockPos? {
        if (blocks.isEmpty()) {
            return null
        }

        val extraBlocks = ArrayList<BlockPos>()
        val sideBlocks = ArrayList<BlockPos>()
        val rowBlocks = ArrayList<BlockPos>()
        val dir = if (facing != Direction.SOUTH && facing != Direction.WEST) 1 else -1
        if (facing.axis == Direction.Axis.Z) {
            val rowAnchor = BlockPos(targetPos.x, targetPos.y, blocks[0].z)
            rowBlocks.add(rowAnchor)
            rowBlocks.add(rowAnchor.above())
            rowBlocks.add(rowAnchor.above().above())
            rowBlocks.add(rowAnchor.west())
            rowBlocks.add(rowAnchor.west().above())
            rowBlocks.add(rowAnchor.west().above().above())
            rowBlocks.add(rowAnchor.east())
            rowBlocks.add(rowAnchor.east().above())
            rowBlocks.add(rowAnchor.east().above().above())
            if (index == 0) {
                for (rowPos in rowBlocks) {
                    sideBlocks.add(rowPos.east(2))
                    sideBlocks.add(rowPos.east(-2))
                }

                for (blockPos in blocks) {
                    if (!sideBlocks.contains(blockPos)) {
                        extraBlocks.add(blockPos)
                    }
                }
            } else {
                for (rowPos in rowBlocks) {
                    sideBlocks.add(rowPos.east(dir * 2 * index))
                }

                for (sidePos in sideBlocks) {
                    if (blocks.contains(sidePos)) {
                        extraBlocks.add(sidePos)
                    }
                }
            }
        }

        if (facing.axis == Direction.Axis.X) {
            val colAnchor = BlockPos(blocks[0].x, targetPos.y, targetPos.z)
            rowBlocks.add(colAnchor)
            rowBlocks.add(colAnchor.above())
            rowBlocks.add(colAnchor.above().above())
            rowBlocks.add(colAnchor.north())
            rowBlocks.add(colAnchor.north().above())
            rowBlocks.add(colAnchor.north().above().above())
            rowBlocks.add(colAnchor.south())
            rowBlocks.add(colAnchor.south().above())
            rowBlocks.add(colAnchor.south().above().above())
            if (index == 0) {
                for (colPos in rowBlocks) {
                    sideBlocks.add(colPos.south(2))
                    sideBlocks.add(colPos.south(-2))
                }

                for (blockPos in blocks) {
                    if (!sideBlocks.contains(blockPos)) {
                        extraBlocks.add(blockPos)
                    }
                }
            } else {
                for (colPos in rowBlocks) {
                    sideBlocks.add(colPos.south(dir * 2 * index))
                }

                for (sidePos in sideBlocks) {
                    if (blocks.contains(sidePos)) {
                        extraBlocks.add(sidePos)
                    }
                }
            }
        }

        return if (extraBlocks.isEmpty()) null else extraBlocks[entity.random.nextInt(extraBlocks.size)]
    }

    /** 1.12 `Blocks.SAPLING.getStateForPlacement(..., meta, ...)`: the sapling block of the replant item. */
    private fun saplingState(stack: ItemStack): BlockState = Block.byItem(stack.item).defaultBlockState()

    @JvmStatic
    fun navigateToTask(entity: KoboldEntity, tribeId: UUID, task: KoboldTask, pos: BlockPos) {
        val dropItem = entity.miningDropItem
        if (dropItem == null) {
            entity.mineCooldown = 24
            entity.cooldownTicks = 0
            entity.setCurrentAction(Action.NULL)
            entity.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
            val master = entity.getMasterPlayer()
            val blocks = task.getMiningTargets()
            if (master != null && blocks.isNotEmpty()) {
                PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, false), master as ServerPlayer)
            }

            KoboldManager.setLeaderKobold(tribeId, entity)
        } else {
            // 1.12 switch on sapling metadata: 3 (jungle) and 5 (dark oak) plant a 2x2
            if (dropItem.item === Items.JUNGLE_SAPLING || dropItem.item === Items.DARK_OAK_SAPLING) {
                val state = saplingState(dropItem)
                entity.level().setBlockAndUpdate(pos, state)
                entity.level().setBlockAndUpdate(pos.north(), state)
                entity.level().setBlockAndUpdate(pos.west(), state)
                entity.level().setBlockAndUpdate(pos.north().west(), state)
            } else {
                entity.level().setBlockAndUpdate(pos, saplingState(dropItem))
            }

            entity.mineCooldown = 24
            entity.cooldownTicks = 0
            entity.miningDropItem = null
            entity.setCurrentAction(Action.NULL)
            entity.setAnchored(false)
            val master = entity.getMasterPlayer()
            val blocks = task.getMiningTargets()
            if (master != null && blocks.isNotEmpty()) {
                PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, false), master as ServerPlayer)
            }

            KoboldManager.setLeaderKobold(tribeId, entity)
        }
    }

    private fun logCloneError(entity: KoboldEntity, pos: BlockPos, ex: IllegalArgumentException) {
        Main.LOGGER.error(
            "Couldn't get an item out of the block that a kobold just destroyed when falling a tree. As a result, the block wasn't added into the kobolds inventory. If you see this message, pls tell trol about it and send her the following stacktrace. Do you maybe remember what block the kobold just removed? Stacktrace follwing:",
        )
        Main.LOGGER.warn("block in question: " + entity.level().getBlockState(pos).block.descriptionId)
        Main.LOGGER.error(ex.message)
    }

    @JvmStatic
    fun startMiningTask(entity: KoboldEntity, tribeId: UUID, pos: BlockPos, task: KoboldTask) {
        if (entity.getCurrentAction() != Action.MINE) {
            mineBlockAt(entity, pos, tribeId)
        } else {
            entity.cooldownTicks--
            if (entity.cooldownTicks <= 0) {
                if (entity.cooldownTicks == 0) {
                    PacketHandler.networkWrapper.sendToAllAround(ResetControllerPacket(entity.getGirlId()), entity.getTargetNetworkPoint())
                }

                if (entity.level().getBlockState(pos).block === Blocks.AIR) {
                    navigateToTask(entity, tribeId, task, pos)
                } else {
                    entity.mineCooldown--
                    if (entity.mineCooldown < 0) {
                        entity.mineCooldown = 24
                        entity.cooldownTicks = 78
                        val targets = HashSet<BlockPos>()
                        val master = entity.getMasterPlayer()

                        for (target in task.getMiningTargets()) {
                            if (entity.level().getBlockState(target).block !== Blocks.AIR) {
                                if (target.x != pos.x || target.z != pos.z) {
                                    try {
                                        val targetState = entity.level().getBlockState(target)
                                        val dropStack = targetState.block.getCloneItemStack(entity.level(), target, targetState)
                                        if (dropStack.item !== Items.AIR) {
                                            entity.canExtractItem(dropStack)
                                        }
                                    } catch (ex: IllegalArgumentException) {
                                        logCloneError(entity, target, ex)
                                    }

                                    entity.miningDropItem = getBlockItem(entity, target)
                                    entity.level().destroyBlock(target, false)
                                    task.removeMiningTarget(target)
                                    targets.add(target)
                                    task.setMiningTargets(targets)
                                    if (master != null) {
                                        PacketHandler.networkWrapper.sendTo(SendBlocksPacket(targets, false), master as ServerPlayer)
                                    }

                                    return
                                }
                            } else {
                                targets.add(target)
                            }
                        }

                        try {
                            val posState = entity.level().getBlockState(pos)
                            val dropStack = posState.block.getCloneItemStack(entity.level(), pos, posState)
                            if (dropStack.item !== Items.AIR) {
                                entity.canExtractItem(dropStack)
                            }
                        } catch (ex: IllegalArgumentException) {
                            logCloneError(entity, pos, ex)
                        }

                        entity.miningDropItem = getBlockItem(entity, pos)
                        entity.level().destroyBlock(pos, false)
                        var logCount = 0

                        for (target in task.getMiningTargets()) {
                            if (entity.level().getBlockState(target).`is`(BlockTags.LOGS)) {
                                logCount++
                            }
                        }

                        val trunkBlocks = HashSet<BlockPos>()

                        for (i in 0 until logCount) {
                            trunkBlocks.add(pos.offset(0, i, 0))
                        }

                        val otherBlocks = HashSet<BlockPos>()

                        for (target in task.getMiningTargets()) {
                            if (!trunkBlocks.contains(target)) {
                                otherBlocks.add(target)
                            }
                        }

                        if (otherBlocks.isNotEmpty() && master != null) {
                            PacketHandler.networkWrapper.sendTo(SendBlocksPacket(otherBlocks, false), master as ServerPlayer)
                        }

                        var height = 1

                        while (true) {
                            val logPos = pos.offset(0, height, 0)
                            val logState = entity.level().getBlockState(logPos)
                            if (logState.`is`(BlockTags.LOGS)) {
                                entity.level().destroyBlock(logPos, false)
                                // 1.12: new EntityFallingBlock(world, x + 0.5, y, z + 0.5, state) with fallTime = 1
                                val fallingBlock = FallingBlockEntity.fall(entity.level(), logPos, logState)
                                fallingBlock.time = 1
                            }

                            if (!task.getMiningTargets().contains(logPos)) {
                                return
                            }

                            height++
                        }
                    }
                }
            }
        }
    }

    /**
     * 1.12: `log` (id 17) meta 1/2/3 → sapling 1/2/3 (spruce/birch/jungle),
     * `log2` (id 162) meta 0/1 → sapling 4/5 (acacia/dark oak), anything else →
     * sapling 0 (oak). 1.21: the same table over the per-wood log/wood blocks.
     */
    @JvmStatic
    fun getBlockItem(entity: KoboldEntity, pos: BlockPos): ItemStack {
        val stack: ItemStack
        try {
            val state = entity.level().getBlockState(pos)
            stack = state.block.getCloneItemStack(entity.level(), pos, state)
        } catch (ex: IllegalArgumentException) {
            Main.LOGGER.error(
                "Couldn't turn a wooden block into an item to get its meta data. As a result the kobold is just gonna plant a oak saplinig instead. If you see this message, pls tell trol about it and send her the following stacktrace. Do you maybe remember what block the kobold just removed? Stacktrace follwing:",
            )
            Main.LOGGER.warn("block in question: " + entity.level().getBlockState(pos).block.descriptionId)
            Main.LOGGER.error(ex.message)
            return ItemStack(Items.OAK_SAPLING, 1)
        }

        val item = stack.item
        return when {
            item === Items.SPRUCE_LOG || item === Items.SPRUCE_WOOD -> ItemStack(Items.SPRUCE_SAPLING, 1)
            item === Items.BIRCH_LOG || item === Items.BIRCH_WOOD -> ItemStack(Items.BIRCH_SAPLING, 1)
            item === Items.JUNGLE_LOG || item === Items.JUNGLE_WOOD -> ItemStack(Items.JUNGLE_SAPLING, 1)
            item === Items.ACACIA_LOG || item === Items.ACACIA_WOOD -> ItemStack(Items.ACACIA_SAPLING, 1)
            item === Items.DARK_OAK_LOG || item === Items.DARK_OAK_WOOD -> ItemStack(Items.DARK_OAK_SAPLING, 1)
            else -> ItemStack(Items.OAK_SAPLING, 1)
        }
    }

    @JvmStatic
    fun mineBlockAt(entity: KoboldEntity, pos: BlockPos, tribeId: UUID) {
        var bestBlock: BlockPos? = null
        val neighbors = ArrayList<BlockPos>()
        if (isFullCube(entity, pos.north().below()) && !isFullBlock(entity, pos.north())) {
            neighbors.add(pos.north())
        }

        if (isFullCube(entity, pos.east().below()) && !isFullBlock(entity, pos.east())) {
            neighbors.add(pos.east())
        }

        if (isFullCube(entity, pos.south().below()) && !isFullBlock(entity, pos.south())) {
            neighbors.add(pos.south())
        }

        if (isFullCube(entity, pos.west().below()) && !isFullBlock(entity, pos.west())) {
            neighbors.add(pos.west())
        }

        for (neighbor in neighbors) {
            if (bestBlock == null) {
                bestBlock = neighbor
            } else {
                val bestDist = Vec3(bestBlock.x + 0.5, bestBlock.y.toDouble(), bestBlock.z + 0.5).distanceTo(entity.position())
                val dist = Vec3(neighbor.x + 0.5, neighbor.y.toDouble(), neighbor.z + 0.5).distanceTo(entity.position())
                if (dist < bestDist) {
                    bestBlock = neighbor
                }
            }
        }

        if (bestBlock == null) {
            KoboldManager.setLeaderKobold(tribeId, entity)
            val master = entity.getMasterPlayer()
            master?.displayClientMessage(Component.literal("Your kobolds cannot fall this tree because it starts underground"), true)
        } else if (!(Math.sqrt(entity.blockPosition().distSqr(Vec3i(bestBlock.x, bestBlock.y, bestBlock.z))) > 1.0)) {
            var yaw = 0.0f
            if (bestBlock.subtract(pos) == BlockPos(0, 0, -1)) {
                yaw = 0.0f
            }

            if (bestBlock.subtract(pos) == BlockPos(1, 0, 0)) {
                yaw = 90.0f
            }

            if (bestBlock.subtract(pos) == BlockPos(0, 0, 1)) {
                yaw = 180.0f
            }

            if (bestBlock.subtract(pos) == BlockPos(-1, 0, 0)) {
                yaw = -90.0f
            }

            entity.setTargetPosition(Vec3(bestBlock.x + 0.5, bestBlock.y.toDouble(), bestBlock.z + 0.5))
            entity.setYawRotation(yaw)
            entity.getEntityData().set(BaseGirlEntity.IS_ANCHORED, true)
            entity.getEntityData().set(KoboldEntity.MINING_WITH_AXE, true)
            entity.setCurrentAction(Action.MINE)
            entity.level().destroyBlock(bestBlock.above(), false)
        } else if (Math.abs(entity.blockPosition().y - bestBlock.y) > 4) {
            entity.syncTribeBlocks(bestBlock)
        } else {
            val standPos = entity.findStandPos(bestBlock)
            entity.getNavigation().moveTo(standPos.x + 0.5, standPos.y.toDouble(), standPos.z + 0.5, 0.35)
            entity.tickPathVelocity()
        }
    }
}
