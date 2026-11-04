package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.StructureMarkerRenderer
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.ChestBlock
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> Tribe-block marker sync between the dragon-staff UI and the
 * server's [KoboldManager] state.
 *
 * SERVER->CLIENT (usual direction): `isBreaking=true` adds the blocks to
 * `StructureMarkerRenderer.renderMarkers(HashSet)` (red/green/blue
 * highlight), `isBreaking=false` removes them (`setMarkers`). Used by
 * [FallTreePacket], [MinePacket], [CancelTaskPacket] and on
 * login (`PlayerIds`).
 *
 * CLIENT->SERVER (dragon-staff clicks on a marked bed/chest): the SERVER-side
 * handler resolves double-blocks (bed halves, double chests) via
 * [WorldUtils.getStatePos] and adds/removes them from
 * the tribe's bed/chest sets, echoing the resolved pair back to the client so
 * the markers match.
 *
 * <b>Pitfall.</b> A single-block payload is the only accepted CLIENT->SERVER
 * form; multi-block payloads are ignored server-side (they are only valid as
 * SERVER->CLIENT echo batches).
 *
 * 1.21: the 1.12 `BlockChest.chestType` comparison is a chest-block identity
 * comparison (chest vs trapped chest).
 */
class SendBlocksPacket : CustomPacketPayload {
    @JvmField
    var blockPositions: HashSet<BlockPos> = HashSet()

    @JvmField
    var isBreaking: Boolean

    constructor(blockPositions: HashSet<BlockPos>, isBreaking: Boolean) {
        this.blockPositions = blockPositions
        this.isBreaking = isBreaking
    }

    constructor(blockPos: BlockPos, isBreaking: Boolean) {
        this.blockPositions.add(blockPos)
        this.isBreaking = isBreaking
    }

    override fun type(): CustomPacketPayload.Type<SendBlocksPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(this.isBreaking)
        buf.writeInt(this.blockPositions.size)

        for (pos in this.blockPositions) {
            buf.writeInt(pos.x)
            buf.writeInt(pos.y)
            buf.writeInt(pos.z)
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SendBlocksPacket>(Main.rl("send_blocks"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SendBlocksPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> read(buf) },
        )

        private fun read(buf: FriendlyByteBuf): SendBlocksPacket {
            val isBreaking = buf.readBoolean()
            val count = buf.readInt()
            val positions = HashSet<BlockPos>()

            for (i in 0 until count) {
                positions.add(BlockPos(buf.readInt(), buf.readInt(), buf.readInt()))
            }

            return SendBlocksPacket(positions, isBreaking)
        }

        private fun isSameChest(world: Level, pos: BlockPos, chestType: ChestBlock): Boolean {
            val block = world.getBlockState(pos).block
            return block is ChestBlock && chestType === block
        }

        @JvmStatic
        fun handle(packet: SendBlocksPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Client.apply(packet)
                return
            }

            val player = ctx.player() as ServerPlayer
            val playerUuid = player.uuid
            val tribeUuid = KoboldManager.getTribeUUID(playerUuid)
            if (tribeUuid != null) {
                if (packet.blockPositions.size == 1) {
                    val world = player.level()

                    for (pos in packet.blockPositions) {
                        val state = world.getBlockState(pos)
                        var pairedPos: BlockPos? = null
                        if (state.block is BedBlock) {
                            pairedPos = WorldUtils.getStatePos(pos, state)
                        }

                        val chestType = state.block
                        if (chestType is ChestBlock) {
                            if (isSameChest(world, pos.north(), chestType)) {
                                pairedPos = pos.north()
                            }

                            if (isSameChest(world, pos.east(), chestType)) {
                                pairedPos = pos.east()
                            }

                            if (isSameChest(world, pos.south(), chestType)) {
                                pairedPos = pos.south()
                            }

                            if (isSameChest(world, pos.west(), chestType)) {
                                pairedPos = pos.west()
                            }
                        }

                        if (pairedPos == null && state.block is BedBlock) {
                            return
                        }

                        if (packet.isBreaking) {
                            if (state.block is BedBlock) {
                                KoboldManager.addTribeBed(tribeUuid, pos)
                                KoboldManager.addTribeBed(tribeUuid, pairedPos)
                            } else {
                                KoboldManager.addTribeChest(tribeUuid, pos)
                                KoboldManager.addTribeChest(tribeUuid, pairedPos)
                            }
                        } else if (state.block is BedBlock) {
                            // jar-verified pairing: beds are added via addTribeBed,
                            // so removal must use removeTribeBed (was removeTribeChest)
                            KoboldManager.removeTribeBed(tribeUuid, pos)
                            KoboldManager.removeTribeBed(tribeUuid, pairedPos)
                        } else {
                            KoboldManager.removeMiningTargetsFor(tribeUuid, pos)
                            KoboldManager.removeMiningTargetsFor(tribeUuid, pairedPos)
                        }

                        val blocks = HashSet<BlockPos>()
                        blocks.add(pos)
                        if (pairedPos != null) {
                            blocks.add(pairedPos)
                        }

                        PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, packet.isBreaking), player)
                    }
                }
            }
        }
    }

    private object Client {
        fun apply(packet: SendBlocksPacket) {
            if (packet.isBreaking) {
                StructureMarkerRenderer.renderMarkers(packet.blockPositions)
            } else {
                StructureMarkerRenderer.setMarkers(packet.blockPositions)
            }
        }
    }
}
