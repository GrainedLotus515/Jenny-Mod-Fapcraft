package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.KoboldTask
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER "mine this area" command from the dragon-staff UI.
 * Sent when the player selects a block and a facing; the tribe kobolds dig a
 * 30-block corridor through it.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Validates the bed
 * requirement (see [FallTreePacket]); computes the 30x3 tunnel blocks with
 * [getMineableBlocks], rejects the request if any of
 * them is unbreakable (bedrock), then registers a `MINE` [KoboldTask]
 * and echoes the target blocks to the client as a
 * [SendBlocksPacket] (markers).
 */
class MinePacket(@JvmField val targetPos: BlockPos, @JvmField val facing: Direction) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MinePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeInt(this.targetPos.x)
        buf.writeInt(this.targetPos.y)
        buf.writeInt(this.targetPos.z)
        buf.writeUtf(this.facing.getName())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<MinePacket>(Main.rl("mine"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, MinePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                MinePacket(
                    BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
                    Direction.byName(buf.readUtf())!!, // java-npe: 1.12 byName returned null for garbage
                )
            },
        )

        @JvmStatic
        fun handle(packet: MinePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid Message @Mine :(")
                return
            }

            val player = ctx.player() as ServerPlayer
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
            if (tribeUuid != null) {
                val memberCount = KoboldManager.getTribeMemberCount(tribeUuid)
                val bedCount = Math.floor(KoboldManager.getTribeBeds(tribeUuid).size / 2.0).toInt()
                if (memberCount > bedCount) {
                    player.sendSystemMessage(
                        Component.literal(
                            String.format(
                                "sUr Tribe will only work for you, if %severyone%s of them has a %sbed",
                                ChatFormatting.RED, ChatFormatting.WHITE, ChatFormatting.RED,
                            ),
                        ),
                    )
                    player.sendSystemMessage(Component.literal(String.format("%s%d/%d Beds", ChatFormatting.YELLOW, bedCount, memberCount)))
                } else {
                    val blocks = getMineableBlocks(packet.targetPos, packet.facing)
                    val world = player.level()

                    for (pos in blocks) {
                        val state = world.getBlockState(pos)
                        if (state.getDestroySpeed(world, pos) < 0.0f) {
                            player.displayClientMessage(Component.literal("This area contains Bedrock and cannot be mined"), true)
                            return
                        }
                    }

                    val task = KoboldTask(packet.targetPos, KoboldTask.TaskType.MINE, blocks, packet.facing)
                    KoboldManager.addTask(tribeUuid, task)
                    PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, true), player)
                }
            }
        }

        /**
         * Computes the 3-block-high, 30-block-long tunnel cross-section starting at
         * `startPos` extending along `facing`. SERVER-side helper for the mine
         * command.
         *
         * @return all [BlockPos] that the kobolds will have to break
         */
        @JvmStatic
        fun getMineableBlocks(startPos: BlockPos, facing: Direction): HashSet<BlockPos> {
            val positions = HashSet<BlockPos>()
            var pos = startPos

            for (i in 0 until 30) {
                positions.add(pos.subtract(getNextBlock(facing)))
                positions.add(pos.subtract(getNextBlock(facing)).above())
                positions.add(pos.subtract(getNextBlock(facing)).above().above())
                positions.add(pos)
                positions.add(pos.above())
                positions.add(pos.above().above())
                positions.add(pos.offset(getNextBlock(facing)))
                positions.add(pos.offset(getNextBlock(facing)).above())
                positions.add(pos.offset(getNextBlock(facing)).above().above())
                pos = pos.offset(facing.normal)
            }

            return positions
        }

        /**
         * Lateral offset of the tunnel cross-section: the vector perpendicular to the
         * facing direction (rotated 90 degrees in the horizontal plane).
         */
        @JvmStatic
        fun getNextBlock(facing: Direction): BlockPos {
            val dirVec = facing.normal
            return BlockPos(dirVec.z, dirVec.y, -dirVec.x)
        }
    }
}
