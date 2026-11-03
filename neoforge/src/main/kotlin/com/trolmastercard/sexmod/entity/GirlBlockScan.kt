package com.trolmastercard.sexmod.entity

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * Spiral block searches around a girl (nearest bed, nearest structure block,
 * all blocks of a class in a radius).
 *
 * Extracted verbatim from [BaseGirlEntity]; the entity keeps its instance
 * methods as delegates, so subclass call sites are unaffected.
 *
 * Port notes: 1.12 biome sets (`HashSet<Biome>`) are sets of
 * `ResourceKey<Biome>` checked with `Holder.is(key)`. 1.12 had a single
 * `Blocks.BED`; after the Flattening there are 16 colored beds, so a
 * [BedBlock] target matches any block in `#minecraft:beds`.
 */
object GirlBlockScan {
    @JvmStatic
    fun getNearestBed(girl: BaseGirlEntity, pos: BlockPos): BlockPos? {
        return findNearestBed(girl, pos, 1)
    }

    @JvmStatic
    fun findNearestBed(girl: BaseGirlEntity, origin: BlockPos, radius: Int): BlockPos? {
        return findNearestStructureBlock(girl, origin, radius, Blocks.RED_BED, 22, 3, null)
    }

    /** 1.12 `state.getBlock() == targetBlock` (any bed when the target is a bed). */
    private fun matches(state: BlockState, targetBlock: Block): Boolean {
        return if (targetBlock is BedBlock) state.`is`(BlockTags.BEDS) else state.block === targetBlock
    }

    private fun isAllowedBiome(girl: BaseGirlEntity, pos: BlockPos, allowedBiomes: HashSet<ResourceKey<Biome>>?): Boolean {
        if (allowedBiomes == null) {
            return true
        }

        val biome = girl.level().getBiome(pos)
        for (key in allowedBiomes) {
            if (biome.`is`(key)) {
                return true
            }
        }

        return false
    }

    @JvmStatic
    fun findNearestStructureBlock(
        girl: BaseGirlEntity,
        origin: BlockPos,
        requiredCount: Int,
        targetBlock: Block,
        maxRadius: Int,
        heightRange: Int,
        allowedBiomes: HashSet<ResourceKey<Biome>>?,
    ): BlockPos? {
        var step = 1
        var dir = -1
        var pos = origin
        var found = 0

        while (step < maxRadius) {
            for (i in 0 until 2) {
                dir *= -1

                for (j in 0 until step) {
                    pos = pos.offset(0, 0, dir)

                    for (h in -heightRange until heightRange + 1) {
                        if (matches(girl.level().getBlockState(pos.offset(0, h, dir)), targetBlock)) {
                            found++
                            if (found >= requiredCount && isAllowedBiome(girl, pos.offset(dir, h, 0), allowedBiomes)) {
                                return pos.offset(0, h, dir)
                            }
                        }
                    }
                }

                for (k in 0 until step) {
                    pos = pos.offset(dir, 0, 0)

                    for (h2 in -heightRange until heightRange + 1) {
                        if (matches(girl.level().getBlockState(pos.offset(dir, h2, 0)), targetBlock)) {
                            found++
                            if (found >= requiredCount && isAllowedBiome(girl, pos.offset(dir, h2, 0), allowedBiomes)) {
                                return pos.offset(dir, h2, 0)
                            }
                        }
                    }
                }

                step++
            }
        }

        return null
    }

    @JvmStatic
    fun findBlocksInRadius(
        girl: BaseGirlEntity,
        center: BlockPos,
        blockClass: Class<*>,
        maxRadius: Int,
        height: Int,
        biomes: HashSet<ResourceKey<Biome>>?,
    ): List<BlockPos> {
        var radius = 1
        var step = -1
        var base = center
        val found = ArrayList<BlockPos>()

        while (radius < maxRadius) {
            for (pass in 0 until 2) {
                step *= -1

                for (i in 0 until radius) {
                    base = base.offset(0, 0, step)

                    for (yOffset in -height until height + 1) {
                        if (blockClass.isInstance(girl.level().getBlockState(base.offset(0, yOffset, step)).block) &&
                            isAllowedBiome(girl, base.offset(step, yOffset, 0), biomes)
                        ) {
                            found.add(base.offset(0, yOffset, step))
                        }
                    }
                }

                for (j in 0 until radius) {
                    base = base.offset(step, 0, 0)

                    for (zOffset in -height until height + 1) {
                        if (blockClass.isInstance(girl.level().getBlockState(base.offset(step, zOffset, 0)).block) &&
                            isAllowedBiome(girl, base.offset(step, zOffset, 0), biomes)
                        ) {
                            found.add(base.offset(step, zOffset, 0))
                        }
                    }
                }

                radius++
            }
        }

        return found
    }
}
