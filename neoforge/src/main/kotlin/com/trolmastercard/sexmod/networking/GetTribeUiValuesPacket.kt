package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.StructureCommandScreen
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.Vector4d
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> Tribe-overview request/response for the dragon-staff
 * `StructureCommandScreen`.
 *
 * CLIENT->SERVER: sent when the staff UI opens (no-argument constructor).
 * The SERVER-side handler collects the tribe's alerted state, saved positions,
 * member positions (one [Vector4d] per member, w = wool color meta) and replies
 * SERVER->CLIENT. If the sender has no tribe it replies with an empty packet.
 *
 * CLIENT-side handler: stores the reply in `KoboldEntity.tribeMembers` and the
 * alerted flag in `StructureCommandScreen.isErasing` — the UI reads those
 * statics to draw the tribe overview.
 *
 * 1.21: the 1.12 `isTribeLeader` flag (true only for decoded packets) is
 * implied by a successful decode and dropped.
 */
class GetTribeUiValuesPacket(
    @JvmField val isValid: Boolean,
    @JvmField val tribeMembers: MutableList<Vector4d>,
) : CustomPacketPayload {
    /** The client request (1.12 no-argument constructor). */
    constructor() : this(false, ArrayList())

    override fun type(): CustomPacketPayload.Type<GetTribeUiValuesPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(this.isValid)
        buf.writeInt(this.tribeMembers.size)

        for (member in this.tribeMembers) {
            buf.writeInt(member.x.toInt())
            buf.writeInt(member.y.toInt())
            buf.writeInt(member.z.toInt())
            buf.writeInt(member.w.toInt())
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<GetTribeUiValuesPacket>(Main.rl("get_tribe_ui_values"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, GetTribeUiValuesPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> read(buf) },
        )

        private fun read(buf: FriendlyByteBuf): GetTribeUiValuesPacket {
            val isValid = buf.readBoolean()
            val count = buf.readInt()
            val members = ArrayList<Vector4d>()

            for (i in 0 until count) {
                members.add(
                    Vector4d(buf.readInt().toDouble(), buf.readInt().toDouble(), buf.readInt().toDouble(), buf.readInt().toDouble()),
                )
            }

            return GetTribeUiValuesPacket(isValid, members)
        }

        @JvmStatic
        fun createEmptyPacket(): GetTribeUiValuesPacket = GetTribeUiValuesPacket(false, ArrayList())

        @JvmStatic
        fun handle(packet: GetTribeUiValuesPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Client.apply(packet)
                return
            }

            val player = ctx.player() as ServerPlayer
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
            if (tribeUuid == null) {
                PacketHandler.networkWrapper.sendTo(createEmptyPacket(), player)
            } else {
                val isAlerted = KoboldManager.isTribeAlerted(tribeUuid)
                val savedPositions = KoboldManager.getTribeSavedPositions(tribeUuid, player.level())
                val membersList = KoboldManager.getTribeMembersList(tribeUuid)
                val positions = ArrayList<Vector4d>()
                var woolColor = KoboldManager.getTribeColor(tribeUuid).getWoolMeta()
                val seenIds = HashSet<UUID>()

                for (kobold in membersList) {
                    if (!kobold.isRemoved) {
                        val girlId = kobold.getGirlId()
                        if (!seenIds.contains(girlId)) {
                            if (kobold.editedColorManually) {
                                woolColor = EyeAndKoboldColor.safeValueOf(kobold.getEntityData().get(AbstractNpcOnlyEntity.CURRENT_ACTION)).getWoolMeta()
                            }

                            positions.add(Vector4d(kobold.x, kobold.y, kobold.z, woolColor.toDouble()))
                            seenIds.add(girlId)
                        }
                    }
                }

                for ((key, pos) in savedPositions) {
                    if (!seenIds.contains(key)) {
                        positions.add(Vector4d(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), woolColor.toDouble()))
                    }
                }

                PacketHandler.networkWrapper.sendTo(GetTribeUiValuesPacket(isAlerted, positions), player)
            }
        }
    }

    private object Client {
        fun apply(packet: GetTribeUiValuesPacket) {
            StructureCommandScreen.isErasing = packet.isValid
            KoboldEntity.tribeMembers = packet.tribeMembers
        }
    }
}
