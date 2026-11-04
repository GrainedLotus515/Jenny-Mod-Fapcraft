package com.trolmastercard.sexmod.worldgen

import com.trolmastercard.sexmod.Main
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Rotation
import java.util.Random

/**
 * Generates the girls' houses in the overworld — the structures where NPC
 * girls spawn and call home (`homePos`).
 *
 * Port notes: 1.12 `WorldGenerator` + `TemplateManager` become
 * `ServerLevel.structureManager` + `StructureTemplate.placeInWorld`. Templates
 * live in `data/sexmod/structure/<name>.nbt` (id `sexmod:<name>`). The 1.12 code
 * referenced the `IWorldGen` constants `worldServer` / `settings`; the intent
 * (overworld template manager, default settings plus rotation) is kept.
 */
open class GirlHouseGenerator(@JvmField var structureName: String) : IWorldGen {

    open fun generateStructure(world: Level, pos: BlockPos) {
        val level = world as? ServerLevel ?: return
        val templateManager = (IWorldGen.worldServer ?: level).structureManager
        val location = Main.rl(this.structureName)
        val template = templateManager.get(location).orElse(null)
        if (template != null) {
            val state = world.getBlockState(pos)
            world.sendBlockUpdated(pos, state, state, 3)
            template.placeInWorld(level, pos, pos, IWorldGen.settings, level.random, 2)
        }
    }

    open fun generateStructureRotated(world: Level, pos: BlockPos, rotation: Rotation) {
        val level = world as? ServerLevel ?: return
        val templateManager = (IWorldGen.worldServer ?: level).structureManager
        val location = Main.rl(this.structureName)
        val template = templateManager.get(location).orElse(null)
        if (template != null) {
            val state = world.getBlockState(pos)
            world.sendBlockUpdated(pos, state, state, 2)
            template.placeInWorld(level, pos, pos, IWorldGen.settings.setRotation(rotation), level.random, 2)
        }
    }

    open fun generate(world: Level, random: Random, pos: BlockPos): Boolean {
        this.generateStructure(world, pos)
        return true
    }

}
