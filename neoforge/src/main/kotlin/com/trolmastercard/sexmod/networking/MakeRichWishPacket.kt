package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.Reference
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER wish item from the Allies lamp's third use —
 * spawns diamonds, emeralds and gold ingots at the requested position.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Spawns three
 * [ItemEntity]s (1-2 of each, via [Reference.RANDOM]) at
 * [wishPos] in the sender's world. Trusted client input — the position is
 * whatever the client sent; the handler does not re-validate distance or
 * ownership.
 */
class MakeRichWishPacket(@JvmField val wishPos: Vec3) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MakeRichWishPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeDouble(wishPos.x)
        buf.writeDouble(wishPos.y)
        buf.writeDouble(wishPos.z)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<MakeRichWishPacket>(Main.rl("make_rich_wish"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, MakeRichWishPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> MakeRichWishPacket(Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())) })

        @JvmStatic
        fun handle(packet: MakeRichWishPacket, ctx: IPayloadContext) {
            val world = ctx.player().level()
            val diamondItem = ItemEntity(world, packet.wishPos.x, packet.wishPos.y, packet.wishPos.z, ItemStack(Items.DIAMOND, Reference.RANDOM.nextInt(2) + 1))
            val goldItem = ItemEntity(world, packet.wishPos.x, packet.wishPos.y, packet.wishPos.z, ItemStack(Items.EMERALD, Reference.RANDOM.nextInt(2) + 1))
            val emeraldItem = ItemEntity(world, packet.wishPos.x, packet.wishPos.y, packet.wishPos.z, ItemStack(Items.GOLD_INGOT, Reference.RANDOM.nextInt(2) + 1))
            world.addFreshEntity(diamondItem)
            world.addFreshEntity(goldItem)
            world.addFreshEntity(emeraldItem)
        }
    }
}
