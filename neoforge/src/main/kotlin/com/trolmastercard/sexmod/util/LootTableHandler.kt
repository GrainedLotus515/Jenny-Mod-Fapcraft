package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.registry.ModItems
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.LootItem
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.util.ObfuscationReflectionHelper
import net.neoforged.neoforge.event.LootTableLoadEvent

/**
 * **Role.** Keys of the mod's loot tables (Jenny, Ellie, Slime, Bia), returned by
 * the girls' `getDefaultLootTable()`. The JSON lives in `data/sexmod/loot_table/`.
 * Static-only holder.
 *
 * Also hosts the Allies Lamp chest-loot injection (1.12
 * `AlliesLampItem.onLootTableLoad`), see [AlliesLampLoot].
 */
object LootTableHandler {
    @JvmField val JENNY_TABLE: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, Main.rl("jenny"))
    @JvmField val ELLIE_TABLE: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, Main.rl("ellie"))
    @JvmField val SLIME_TABLE: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, Main.rl("slime"))
    @JvmField val BIA_TABLE: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, Main.rl("bia"))

    /**
     * Adds the Allies Lamp (weight 5, quality 0) to the third pool (1.12 `pool2`,
     * or the fourth, `pool3`, if present) of the abandoned mineshaft, desert pyramid,
     * simple dungeon and woodland mansion chests. NeoForge names unnamed vanilla
     * pools `pool0`, `pool1`, ... like Forge 1.12 named them `main`, `pool1`, ...,
     * so the same pool is targeted.
     *
     * 1.21 loot pools are immutable and have no `addEntry`; the pool's entry list
     * is replaced reflectively while the table is still unfrozen (the event fires
     * before the table is frozen).
     *
     * Registered by `ForgeEventHandler.registerAll` on the common path. The
     * 1.12 handler lived in `AlliesLampItem`; it must not be registered twice.
     */
    object AlliesLampLoot {
        private val CHEST_TABLES: Set<ResourceKey<LootTable>> = hashSetOf(
            BuiltInLootTables.ABANDONED_MINESHAFT,
            BuiltInLootTables.DESERT_PYRAMID,
            BuiltInLootTables.SIMPLE_DUNGEON,
            BuiltInLootTables.WOODLAND_MANSION,
        )

        @SubscribeEvent
        fun onLootTableLoad(event: LootTableLoadEvent) {
            if (CHEST_TABLES.any { it.location() == event.name }) {
                var pool: LootPool? = event.table.getPool("pool3")
                if (pool == null) {
                    pool = event.table.getPool("pool2")
                }

                if (pool != null) {
                    addEntry(pool, LootItem.lootTableItem(ModItems.ALLIES_LAMP.get()).setWeight(5).setQuality(0).build())
                }
            }
        }

        private fun addEntry(pool: LootPool, entry: LootPoolEntryContainer) {
            try {
                val field = ObfuscationReflectionHelper.findField(LootPool::class.java, "entries")
                @Suppress("UNCHECKED_CAST")
                val entries = field.get(pool) as List<LootPoolEntryContainer>
                field.set(pool, entries + entry)
            } catch (e: Exception) {
                Main.LOGGER.error("could not add the allies lamp to a chest loot pool", e)
            }
        }
    }
}
