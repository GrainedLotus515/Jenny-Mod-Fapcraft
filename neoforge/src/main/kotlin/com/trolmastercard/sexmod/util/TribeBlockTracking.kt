package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendBlocksPacket
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * <b>Role.</b> Keeps the tribes' bed/chest sets in sync with the world for
 * [KoboldManager]: placing a chest next to a tribe bed claims the new half
 * for that tribe, breaking a tracked chest or bed removes it, and the master
 * gets the block markers echoed back in both cases.
 *
 * <b>Pitfalls.</b> Server side only — the callers check `level.isClientSide`,
 * as does this class. The chest branch tracks the bed set and the bed branch
 * tracks the chest set: the two sets are crossed on purpose (jar-verified
 * against the deobf), so "fixing" the names would move the markers to the wrong
 * blocks. Break events read the block from the world, not from the event.
 */
object TribeBlockTracking {
    @JvmStatic
    fun place(world: Level, pos: BlockPos, state: BlockState, tribes: Map<UUID, KoboldManager.Tribe>) {
        if (!world.isClientSide) {
            if (state.block is ChestBlock) {
                // 1.12 BlockChest.chestType: the chest block identity (chest / trapped chest)
                val chestType = world.getBlockState(pos).block
                val pairedPos = KoboldBlockLookup.findPairedChest(world, pos, chestType)
                if (pairedPos != null) {
                    for ((key, tribe) in tribes) {
                        if (tribe.tribeBeds.contains(pairedPos)) {
                            tribe.tribeBeds.add(pos)
                            val masterUuid = KoboldManager.findTribeIdWith(key)
                            if (masterUuid != null) {
                                val master = world.getPlayerByUUID(masterUuid) as ServerPlayer?
                                if (master != null) {
                                    PacketHandler.networkWrapper.sendTo(SendBlocksPacket(pos, true), master)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun breakBlock(world: Level, pos: BlockPos, tribes: Map<UUID, KoboldManager.Tribe>) {
        if (!world.isClientSide) {
            val state = world.getBlockState(pos)
            val block = state.block
            if (block is ChestBlock) {
                for ((key, tribe) in tribes) {
                    if (tribe.tribeBeds.contains(pos)) {
                        tribe.tribeBeds.remove(pos)
                        val masterUuid = KoboldManager.findTribeIdWith(key)
                        if (masterUuid != null) {
                            val master = world.getPlayerByUUID(masterUuid) as ServerPlayer?
                            if (master != null) {
                                PacketHandler.networkWrapper.sendTo(SendBlocksPacket(pos, false), master)
                            }
                        }
                    }
                }
            }

            if (block is BedBlock) {
                for ((key, tribe2) in tribes) {
                    if (tribe2.tribeChests.contains(pos)) {
                        val pairedPos = WorldUtils.getStatePos(pos, state)
                        tribe2.tribeChests.remove(pos)
                        tribe2.tribeChests.remove(pairedPos)
                        val masterUuid2 = KoboldManager.findTribeIdWith(key)
                        if (masterUuid2 != null) {
                            val master2 = world.getPlayerByUUID(masterUuid2) as ServerPlayer?
                            if (master2 != null) {
                                val blocks = HashSet<BlockPos?>()
                                blocks.add(pos)
                                blocks.add(pairedPos)
                                @Suppress("UNCHECKED_CAST")
                                PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks as HashSet<BlockPos>, false), master2)
                            }
                        }
                    }
                }
            }
        }
    }
}
