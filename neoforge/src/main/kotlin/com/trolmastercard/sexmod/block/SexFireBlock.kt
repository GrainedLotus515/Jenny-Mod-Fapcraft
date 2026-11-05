package com.trolmastercard.sexmod.block

import com.mojang.serialization.MapCodec
import com.trolmastercard.sexmod.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * **Role.** Inert fire block registered as `sexmod:fire` — its scheduled
 * [tick] is deliberately empty so the block never spreads, burns out or
 * burns blocks. Used by the galath/dragon effects that place a non-spreading
 * fire block. Registration lives in `ModBlocks` (`fire`, no BlockItem, as in 1.12).
 *
 * Extends [FireBlock] so the `north/east/south/west/up` properties used by
 * `blockstates/fire.json` exist.
 */
class SexFireBlock : FireBlock {
    constructor() : this(
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.FIRE)
            .replaceable()
            .noCollission()
            .instabreak()
            .lightLevel { 15 }
            .sound(SoundType.WOOL)
            .pushReaction(PushReaction.DESTROY)
    )

    constructor(properties: BlockBehaviour.Properties) : super(properties)

    @Suppress("UNCHECKED_CAST")
    override fun codec(): MapCodec<FireBlock> = CODEC as MapCodec<FireBlock>

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
    }

    companion object {
        @JvmField
        val CODEC: MapCodec<SexFireBlock> = simpleCodec(::SexFireBlock)

        /** The registered block (1.12 static singleton). */
        @JvmStatic
        val FIRE: Block
            get() = ModBlocks.FIRE.get()

        /** Kept for call sites; registration happens in `ModBlocks`. */
        @JvmStatic
        fun register() {
        }
    }
}
