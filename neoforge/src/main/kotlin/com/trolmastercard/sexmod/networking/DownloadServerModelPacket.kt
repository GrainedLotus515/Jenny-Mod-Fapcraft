package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * **Role.** Bidirectional custom-model transfer for whitelisted servers.
 *
 * CLIENT->SERVER: the client sends the list of model names it still wants
 * (missing model dirs). The SERVER-side handler (on the main thread)
 * reads each model's `.cfg`/`.png`/`.geo.json` trio from
 * `sexmod_custom_models/<name>/` and sends one SERVER->CLIENT packet per
 * file, tagging each with a `modelIndex` = total number of packets to
 * expect.
 *
 * CLIENT-side handler: writes the received file into
 * `sexmod/custom_models/<server>/<name>/` and prints download progress;
 * after the last file (`packetCounter` reaching `modelIndex`) it
 * reloads the custom-model registry
 * ([ServerWhitelistManager.getModelCount]). The whole exchange is
 * gated by [ServerWhitelistManager.isGlobalRenderingDisabled] — a server
 * the player did not whitelist never triggers or accepts downloads.
 *
 * **Pitfall.** The two directions use distinct wire formats. 1.12 branched on
 * the sending side; 1.21 has one codec per payload, so a leading boolean
 * (`true` = file chunk, `false` = name request) selects the format. The client
 * accepts file chunks only for whitelisted servers (checked in the handler; 1.12
 * checked it while decoding).
 */
class DownloadServerModelPacket : CustomPacketPayload {
    @JvmField
    var modelNames: MutableList<String> = ArrayList()

    @JvmField
    var modelData: ByteArray? = null

    @JvmField
    var packetType: DownloadServerModelPacketType? = null

    @JvmField
    var modelName: String? = null

    @JvmField
    var modelIndex: Int = 0

    constructor(modelNames: List<String>) {
        this.modelNames = ArrayList(modelNames)
    }

    constructor(modelData: ByteArray, packetType: DownloadServerModelPacketType, modelName: String) {
        this.modelData = modelData
        this.packetType = packetType
        this.modelName = modelName
    }

    fun getModelIndex(): Int = this.modelIndex

    fun setModelIndex(modelIndex: Int) {
        this.modelIndex = modelIndex
    }

    override fun type(): CustomPacketPayload.Type<DownloadServerModelPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        val data = this.modelData
        if (data == null) {
            // CLIENT->SERVER request
            buf.writeBoolean(false)
            buf.writeInt(this.modelNames.size)

            for (name in this.modelNames) {
                buf.writeUtf(name)
            }
        } else {
            // SERVER->CLIENT file chunk
            buf.writeBoolean(true)
            buf.writeUtf(this.modelName!!)
            buf.writeUtf(this.packetType.toString())
            buf.writeInt(this.modelIndex)
            buf.writeByteArray(data)
        }
    }

    object Handler {
        @JvmField
        var packetCounter: Int = 0

        @JvmStatic
        fun onMessage(packet: DownloadServerModelPacket, ctx: IPayloadContext) {
            if (ctx.flow().isServerbound) {
                if (packet.modelData != null) {
                    Main.LOGGER.warn("received an invalid Message @DownloadServerModel :(")
                    return
                }
                val server = ServerLifecycleHooks.getCurrentServer() ?: return
                val player = ctx.player() as ServerPlayer
                val requested: List<String> = packet.modelNames
                val packets = ArrayList<DownloadServerModelPacket>()

                for (typeEnding in requested) {
                    val serverDir = "sexmod_custom_models/$typeEnding"

                    for (fileType in DownloadServerModelPacketType.entries) {
                        val modelFile = File(serverDir + "/" + typeEnding + fileType.ending)
                        if (!modelFile.exists()) {
                            Main.LOGGER.warn(modelFile.absolutePath + " doesnt exist lol")
                        } else {
                            val fileBytes: ByteArray
                            try {
                                fileBytes = Files.readAllBytes(modelFile.toPath())
                            } catch (ioEx: IOException) {
                                throw RuntimeException(ioEx)
                            }

                            packets.add(DownloadServerModelPacket(fileBytes, fileType, typeEnding))
                        }
                    }
                }

                val totalPackets = packets.size

                for (chunkPacket in packets) {
                    chunkPacket.setModelIndex(totalPackets)
                    server.execute { PacketHandler.networkWrapper.sendTo(chunkPacket, player) }
                }
                return
            }

            if (packet.modelData == null) {
                Main.LOGGER.warn("received an invalid Message @DownloadServerModel :(")
                return
            }

            Client.onClientMessage(packet)
        }

        /** CLIENT only. */
        @JvmStatic
        fun sendModelMessage(message: String) {
            Client.sendModelMessage(message)
        }

        /** CLIENT only. */
        @JvmStatic
        fun reloadServerModels() {
            Client.reloadServerModels()
        }
    }

    enum class DownloadServerModelPacketType(@JvmField var ending: String) {
        CFG(".cfg"),
        PNG(".png"),
        GEO(".geo.json"),
    }

    /** Client-only half (PORTING.md §2.3 rule C). */
    private object Client {
        fun sendModelMessage(message: String) {
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(message))
        }

        fun reloadServerModels() {
            Minecraft.getInstance().execute { ServerWhitelistManager.getModelCount(true) }
        }

        fun onClientMessage(packet: DownloadServerModelPacket) {
            if (!ServerWhitelistManager.isGlobalRenderingDisabled()) {
                return
            }

            val modelName = packet.modelName!!
            val packetType = packet.packetType!!
            val modelData = packet.modelData!!
            val groupPath = ServerWhitelistManager.getCurrentGroup() + "/" + modelName
            val dirFile = File(groupPath)
            dirFile.mkdirs()
            val modelFile = File(groupPath + "/" + modelName + packetType.ending)

            try {
                FileOutputStream(modelFile).use { outputStream -> outputStream.write(modelData) }
            } catch (ioEx: Throwable) {
                Main.LOGGER.error("could not close the model file stream", ioEx)
            }

            var downloadedCount = 0
            val totalTypes = DownloadServerModelPacketType.entries.size

            for (type in DownloadServerModelPacketType.entries) {
                if (File(groupPath + "/" + modelName + type.ending).exists()) {
                    downloadedCount++
                }
            }

            if (downloadedCount == totalTypes) {
                Handler.sendModelMessage(
                    String.format("%sSuccessfully downloaded the custom model '%s%s%s'!", ChatFormatting.GREEN, ChatFormatting.YELLOW, modelName, ChatFormatting.GREEN)
                )
            } else {
                Handler.sendModelMessage(
                    String.format(
                        "%sdownloading custom model '%s%s%s' (%s/%s)...", ChatFormatting.GRAY, ChatFormatting.YELLOW, modelName, ChatFormatting.GRAY, downloadedCount, totalTypes
                    )
                )
            }

            // guard: a stale counter from a previous (interrupted) batch would trip
            // the completion check early — restart the batch count when the server
            // starts a new one
            if (Handler.packetCounter >= packet.modelIndex) {
                Handler.packetCounter = 0
            }

            if (++Handler.packetCounter < packet.modelIndex) {
                return
            }

            Handler.packetCounter = 0
            Handler.reloadServerModels()
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<DownloadServerModelPacket>(Main.rl("download_server_model"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, DownloadServerModelPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                if (buf.readBoolean()) {
                    val modelName = buf.readUtf()
                    val packetType = DownloadServerModelPacketType.valueOf(buf.readUtf())
                    val modelIndex = buf.readInt()
                    val data = buf.readByteArray()
                    val p = DownloadServerModelPacket(data, packetType, modelName)
                    p.modelIndex = modelIndex
                    p
                } else {
                    val nameCount = buf.readInt()
                    val names = ArrayList<String>()

                    for (i in 0 until nameCount) {
                        names.add(buf.readUtf())
                    }
                    DownloadServerModelPacket(names)
                }
            })

        /** Runs on the main thread (NeoForge default; 1.12 used addScheduledTask). */
        @JvmStatic
        fun handle(packet: DownloadServerModelPacket, ctx: IPayloadContext) {
            Handler.onMessage(packet, ctx)
        }
    }
}
