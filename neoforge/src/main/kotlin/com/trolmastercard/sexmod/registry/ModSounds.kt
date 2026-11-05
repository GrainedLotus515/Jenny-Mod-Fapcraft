package com.trolmastercard.sexmod.registry

import com.google.gson.JsonParser
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.SoundHandler
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvent
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.io.InputStreamReader
import java.util.function.Supplier

/**
 * Sound event registry.
 *
 * 1. Every element of every [SoundHandler] array is registered with the
 *    existing instance (`SOUND_EVENTS.register(path) { existingInstance }`), so
 *    `SoundHandler.MISC_PLOB[0]` & co. keep their identity. Names follow the 1.12
 *    rule (`field.lowercase().replace('_','.')` + "." + category + index).
 * 2. Every other event declared in `assets/sexmod/sounds.json` (no Java constant,
 *    e.g. `girls.jenny.aftersessionmoan.bjmoan.bjmoan0`) is registered too, read
 *    from the classpath at construction time, so all `sounds.json` entries resolve.
 *
 * Two array entries (`girls.jenny.hmph.hmph4`, `girls.kobold.lightbreathing.lightbreathing8`)
 * have no `sounds.json` definition, exactly as in 1.12; they are still registered.
 */
object ModSounds {
    @JvmField
    val SOUND_EVENTS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Main.MOD_ID)

    /** Every registered sound holder by path (array sounds first, then the sounds.json-only ones). */
    @JvmField
    val HOLDERS: LinkedHashMap<String, DeferredHolder<SoundEvent, SoundEvent>> = LinkedHashMap()

    init {
        for ((path, event) in SoundHandler.ALL_SOUND_EVENTS) {
            HOLDERS[path] = SOUND_EVENTS.register(path, Supplier { event })
        }
        for (path in readSoundsJsonKeys()) {
            if (!HOLDERS.containsKey(path)) {
                HOLDERS[path] = SOUND_EVENTS.register(path, Supplier { SoundEvent.createVariableRangeEvent(Main.rl(path)) })
            }
        }
    }

    private fun readSoundsJsonKeys(): List<String> {
        return try {
            val stream = ModSounds::class.java.getResourceAsStream("/assets/sexmod/sounds.json")
            if (stream == null) {
                Main.LOGGER.warn("sounds.json not found on the classpath; only SoundHandler sounds are registered")
                emptyList()
            } else {
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    JsonParser.parseReader(reader).asJsonObject.keySet().toList()
                }
            }
        } catch (e: Exception) {
            Main.LOGGER.error("could not read sounds.json", e)
            emptyList()
        }
    }

    @JvmStatic
    fun register(bus: IEventBus) {
        SOUND_EVENTS.register(bus)
    }
}
