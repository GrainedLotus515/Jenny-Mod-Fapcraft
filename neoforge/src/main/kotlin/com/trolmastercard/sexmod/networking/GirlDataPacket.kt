package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.GirlScreenBase
import com.trolmastercard.sexmod.entity.NpcType
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** SERVER->CLIENT payload for the girl customization screen. Sent when
 * the player right-clicks a girl with the editor wand (and when the horny potion
 * runs out); carries the per-NPC-type "girl-specific" custom model data
 * (`sexmod:GirlSpecific<type>`) stored on the sender's player NBT.
 *
 * 1.21: the map is filled in the [Player] constructor instead of at encode time,
 * because integrated-server payloads are handed over without being encoded.
 *
 * **Handler.** CLIENT-side. Opens a [GirlScreenBase] populated with the received data.
 */
class GirlDataPacket private constructor(@JvmField val girlSpecificModels: HashMap<NpcType, String>) : CustomPacketPayload {
    constructor(player: Player) : this(collect(player))

    override fun type(): CustomPacketPayload.Type<GirlDataPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeInt(girlSpecificModels.size)

        for ((key, value) in girlSpecificModels) {
            buf.writeUtf(key.toString())
            buf.writeUtf(value, PacketHandler.MAX_STRING)
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<GirlDataPacket>(Main.rl("girl_data"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, GirlDataPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                val map = HashMap<NpcType, String>()
                val count = buf.readInt()
                for (i in 0 until count) {
                    map[NpcType.valueOf(buf.readUtf())] = buf.readUtf(PacketHandler.MAX_STRING)
                }
                GirlDataPacket(map)
            })

        private fun collect(player: Player): HashMap<NpcType, String> {
            val map = HashMap<NpcType, String>()
            for (type in NpcType.values()) {
                if (type.hasSpecifics) {
                    val value = player.persistentData.getString("sexmod:GirlSpecific$type")
                    if ("" != value) {
                        map[type] = value
                    }
                }
            }
            return map
        }

        @JvmStatic
        fun handle(packet: GirlDataPacket, ctx: IPayloadContext) {
            if (ctx.flow().isClientbound) {
                applyGirlData(packet.girlSpecificModels)
            }
        }

        @JvmStatic
        fun applyGirlData(data: HashMap<NpcType, String>) {
            Client.open(data)
        }
    }

    /** Client-only half (§2.3 Rule C). */
    private object Client {
        fun open(data: HashMap<NpcType, String>) {
            val minecraft = Minecraft.getInstance()
            minecraft.execute { minecraft.setScreen(GirlScreenBase(data)) }
        }
    }
}
