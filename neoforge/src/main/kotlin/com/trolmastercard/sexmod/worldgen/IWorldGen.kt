package com.trolmastercard.sexmod.worldgen

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * Interface for world-gen hooks used by ConfigWorldGenHandler.
 *
 * Port note: the 1.12 interface held two constants evaluated at class-load
 * time (`worldServer` = overworld, `settings` = default placement settings).
 * In 1.21 the server does not exist at class-load time, so [worldServer] is
 * a getter resolving the current server's overworld and [settings] returns a
 * fresh default [StructurePlaceSettings] each time (the 1.12 shared instance
 * was mutated by `setRotation`, leaking the last rotation into later
 * unrotated placements).
 */
interface IWorldGen {
    companion object {
        @JvmStatic
        val worldServer: ServerLevel?
            get() = ServerLifecycleHooks.getCurrentServer()?.overworld()

        @JvmStatic
        val settings: StructurePlaceSettings
            get() = StructurePlaceSettings().setIgnoreEntities(false).setMirror(Mirror.NONE).setRotation(Rotation.NONE)
    }
}
