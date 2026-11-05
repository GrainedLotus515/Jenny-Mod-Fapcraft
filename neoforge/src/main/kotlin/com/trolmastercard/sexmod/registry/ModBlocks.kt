package com.trolmastercard.sexmod.registry

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.block.SexFireBlock
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Block registry: `sexmod:fire` ([SexFireBlock]) with no BlockItem, same as 1.12.
 */
object ModBlocks {
    @JvmField
    val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(Main.MOD_ID)

    @JvmField
    val FIRE: DeferredBlock<SexFireBlock> = BLOCKS.register("fire", Supplier { SexFireBlock() })

    @JvmStatic
    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
    }
}
