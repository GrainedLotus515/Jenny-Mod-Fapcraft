package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.registry.ModEntities

/**
 * <b>Role.</b> One-shot entity registration for the whole mod: every NPC,
 * player-form, egg/projectile and filler entity plus the natural spawn rules
 * (slimes in swamps, bees in forests, pyrocinical/Manglelie in the Nether).
 *
 * Port note: in 1.21 entity types are registered through the
 * `DeferredRegister` in [ModEntities] (ids, sizes, tracking ranges and spawn
 * egg colours are listed in PORTING.md §2.5), and the natural spawns are
 * data-driven biome modifiers (`data/sexmod/neoforge/biome_modifier`). This
 * object is kept as a thin delegate so the old call site still compiles.
 *
 * <b>Pitfalls.</b> The resource ids (`sexmod:jenny`, `sexmod:player_jenny`, ...)
 * must stay stable across versions (they are stored in the world/entities).
 * Player-form entities are registered without velocity updates; spawn-able
 * entities use a 50-block tracking range and egg colours.
 */
object SexModEntities {
    /**
     * Kept for call sites. Registration itself happens when `Main` calls
     * [ModEntities.register] on the mod bus; calling this is a no-op that only
     * forces [ModEntities] to be class-loaded.
     */
    @JvmStatic
    fun registerEntities() {
        ModEntities.toString()
    }
}
