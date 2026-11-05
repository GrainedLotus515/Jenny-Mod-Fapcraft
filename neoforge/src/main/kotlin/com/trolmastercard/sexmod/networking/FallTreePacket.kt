package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.KoboldTask
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER request to fell a tree with the tribe. Sent from the
 * dragon-staff UI when the player marks a log ("cut tree").
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Validates the tribe
 * bed requirement (members may not exceed `beds/2`); if it holds, walks
 * down the log with [findGroundPos] to the ground block,
 * computes the connected log blocks via
 * [KoboldTask.findConnectedBlocks] (which also
 * creates the `FALL_TREE` task) and echoes the blocks back to the client as
 * a [SendBlocksPacket] for marker highlighting.
 *
 * 1.21: the 1.12 `BlockLog` check is the `#minecraft:logs` tag.
 */
class FallTreePacket(@JvmField val treePos: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FallTreePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeInt(this.treePos.x)
        buf.writeInt(this.treePos.y)
        buf.writeInt(this.treePos.z)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<FallTreePacket>(Main.rl("fall_tree"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FallTreePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> FallTreePacket(BlockPos(buf.readInt(), buf.readInt(), buf.readInt())) },
        )

        @JvmStatic
        fun handle(packet: FallTreePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid Message @FallTree :(")
                return
            }

            val player = ctx.player() as ServerPlayer
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
            if (tribeUuid == null) {
                Main.LOGGER.info("not tribe for player")
            } else {
                val memberCount = KoboldManager.getTribeMemberCount(tribeUuid)
                val bedCount = Math.floor(KoboldManager.getTribeBeds(tribeUuid).size / 2.0).toInt()
                if (memberCount > bedCount) {
                    player.sendSystemMessage(
                        Component.literal(
                            String.format(
                                "Ur Tribe will only work for you, if %severyone%s of them has a %sbed",
                                ChatFormatting.RED, ChatFormatting.WHITE, ChatFormatting.RED,
                            ),
                        ),
                    )
                    player.sendSystemMessage(Component.literal(String.format("%s%d/%d Beds", ChatFormatting.YELLOW, bedCount, memberCount)))
                } else {
                    val world = player.level()
                    val groundPos = findGroundPos(world, packet.treePos)
                    val blocks = KoboldTask.findConnectedBlocks(world, groundPos, tribeUuid)
                    PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, true), player)
                }
            }
        }

        /**
         * Recursively follows log blocks downwards (including diagonals)
         * to find the lowest ground block of the tree. SERVER-side helper; recursion
         * terminates because the tree has finite height.
         *
         * @return the [BlockPos] just above the first non-log block
         */
        @JvmStatic
        fun findGroundPos(world: Level, pos: BlockPos): BlockPos {
            for (offset in DOWN_OFFSETS) {
                val next = pos.offset(offset[0], -1, offset[1])
                if (world.getBlockState(next).`is`(BlockTags.LOGS)) {
                    return findGroundPos(world, next)
                }
            }

            return pos
        }

        /** (x, z) offsets one block down, in the Java check order. */
        private val DOWN_OFFSETS: Array<IntArray> = arrayOf(
            intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(-1, -1), intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1),
        )
    }
}
