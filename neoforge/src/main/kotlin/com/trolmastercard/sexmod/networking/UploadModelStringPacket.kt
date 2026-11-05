package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER upload of a custom model code (+ optional part-id
 * list) for a girl, from the clothing editor / `/setmodelcode` command.
 *
 * **Handler.** SERVER-side, on the main thread. For NPC girls the
 * code/part list is stored on the entity (`setCustomModelCode`/
 * `setCustomPartList`); for transformed player-girls it is persisted on
 * the player's NBT (`sexmod:CustomModel<type>` and, if the part list
 * passed validation, `sexmod:GirlSpecific<type>`).
 *
 * **Validation.** [Handler.isValidModelCode] rejects a
 * part-id list unless every id is strictly smaller than the girl's current ids —
 * part ids encode nested "hide parent, show child" choices, so a non-decreasing
 * list would be a no-op or a rollback.
 */
class UploadModelStringPacket : CustomPacketPayload {
    @JvmField
    var modelCode: String

    @JvmField
    var partIds: MutableList<Int> = ArrayList()

    @JvmField
    var girlUUID: UUID

    constructor(modelCode: String, girlUUID: UUID) {
        this.modelCode = modelCode
        this.girlUUID = girlUUID
    }

    constructor(modelCode: String, girlUUID: UUID, partIds: List<Int>) {
        this.modelCode = modelCode
        this.girlUUID = girlUUID
        this.partIds = ArrayList(partIds)
    }

    override fun type(): CustomPacketPayload.Type<UploadModelStringPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(this.modelCode, PacketHandler.MAX_STRING)
        buf.writeUtf(this.girlUUID.toString())
        buf.writeInt(this.partIds.size)

        for (partId in this.partIds) {
            buf.writeInt(partId)
        }
    }

    object Handler {
        @JvmStatic
        fun onMessage(packet: UploadModelStringPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @UploadModelString :(")
                return
            }

            Main.LOGGER.info(
                "[sexmod-pack] UploadModelString recv girl=" + packet.girlUUID +
                    " partIds=" + packet.partIds + " modelCode='" + packet.modelCode + "'"
            )
            val girl = BaseGirlEntity.getServerGirlEntity(packet.girlUUID)
            if (girl == null) {
                Main.LOGGER.warn("[sexmod-pack] girl NOT FOUND on server: " + packet.girlUUID)
                return
            }
            Main.LOGGER.info(
                "[sexmod-pack] girl found: " + girl.javaClass.simpleName +
                    " dnaBefore='" + girl.getCustomPartListCode() + "'"
            )

            if (packet.partIds.size > 0) {
                val valid = isValidModelCode(girl, packet.partIds)
                Main.LOGGER.info("[sexmod-pack] validation=" + valid + " (currentIds=" + girl.getCustomPartIdList() + ", sent=" + packet.partIds + ")")
                if (valid) {
                    girl.setCustomPartList(packet.partIds)
                    Main.LOGGER.info("[sexmod-pack] DNA after setCustomPartList='" + girl.getCustomPartListCode() + "'")
                }

                if (girl !is AbstractPlayerGirlEntity) {
                    girl.setCustomModelCode(packet.modelCode)
                } else {
                    val player = ctx.player() as ServerPlayer
                    val entityData = player.persistentData
                    val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
                    if (playerGirl != null) {
                        val npcType = NpcType.getNpcType(playerGirl)
                        entityData.putString("sexmod:CustomModel" + npcType.toString(), packet.modelCode)
                        if (valid) {
                            entityData.putString("sexmod:GirlSpecific" + npcType.toString(), BaseGirlEntity.encodePartIdList(packet.partIds))
                        }
                    }
                }
            } else if (girl !is AbstractPlayerGirlEntity) {
                girl.setCustomModelCode(packet.modelCode)
            } else {
                val player2 = ctx.player() as ServerPlayer
                val entityData2 = player2.persistentData
                val playerGirl2 = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player2)
                if (playerGirl2 != null) {
                    val npcType2 = NpcType.getNpcType(playerGirl2)
                    entityData2.putString("sexmod:CustomModel" + npcType2.toString(), packet.modelCode)
                }
            }
        }

        /**
         * True if every part id in `partIds` is strictly smaller than the
         * corresponding current id of the girl. SERVER-side validation for the custom
         * part list; a mismatched list size is invalid.
         */
        @JvmStatic
        fun isValidModelCode(girl: BaseGirlEntity, partIds: List<Int>): Boolean {
            val currentIds = girl.getCustomPartIdList()

            try {
                for (i in currentIds.indices) {
                    if (currentIds[i] <= partIds[i]) {
                        return false
                    }
                }

                return true
            } catch (exception: IndexOutOfBoundsException) {
                return false
            }
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UploadModelStringPacket>(Main.rl("upload_model_string"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UploadModelStringPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                val modelCode = buf.readUtf(PacketHandler.MAX_STRING)
                val girlUUID = UUID.fromString(buf.readUtf())
                val count = buf.readInt()
                val ids = ArrayList<Int>()

                for (i in 0 until count) {
                    ids.add(buf.readInt())
                }
                UploadModelStringPacket(modelCode, girlUUID, ids)
            })

        /** Runs on the server main thread (NeoForge default; 1.12 used addScheduledTask). */
        @JvmStatic
        fun handle(packet: UploadModelStringPacket, ctx: IPayloadContext) {
            Handler.onMessage(packet, ctx)
        }
    }
}
