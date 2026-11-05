package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import net.minecraft.sounds.SoundEvent

/**
 * **Role.** Sound-event arrays for every girl/effect. In 1.12 they were filled
 * and registered into the Forge sound registry by [registerSounds] via
 * reflection over the class fields; in 1.21 every array is created eagerly here
 * (`SoundEvent.createVariableRangeEvent`) and `registry/ModSounds` registers the
 * very same instances, so `SoundHandler.MISC_PLOB[0]` keeps its identity.
 * [randomSound] picks a random variant without repeating the same one twice in
 * a row (per-array history in [lastRandomSound]).
 *
 * **Pitfall.** The registry-name derivation (`FIELD_NAME.lowercase()` with
 * '_'->'.' plus the field's own name as the suffix) is load-bearing —
 * renaming a field changes the sound path and breaks the sound files. Array
 * sizes must match the number of audio files per sound.
 */
object SoundHandler {
    /**
     * Every sound event created by [soundArray], keyed by its registry path, in
     * declaration order. Must be declared before the arrays (Kotlin initialises
     * object properties in declaration order). Read by `ModSounds`.
     */
    @JvmField val ALL_SOUND_EVENTS: LinkedHashMap<String, SoundEvent> = LinkedHashMap()

    /** 1.12 `registerSounds()` name rule, applied eagerly per array field. */
    private fun soundArray(fieldName: String, size: Int): Array<SoundEvent> {
        val soundName = fieldName.lowercase().replace("_", ".")
        val parts = soundName.split(".")
        val category = if (parts.size > 2) parts[2] else parts[1]
        return Array(size) { i ->
            val path = String.format("%s.%s%s", soundName, category, i)
            val event = SoundEvent.createVariableRangeEvent(Main.rl(path))
            ALL_SOUND_EVENTS[path] = event
            event
        }
    }

    @JvmField val MISC_PLOB: Array<SoundEvent> = soundArray("MISC_PLOB", 1)
    @JvmField val MISC_BELLJINGLE: Array<SoundEvent> = soundArray("MISC_BELLJINGLE", 1)
    @JvmField val MISC_BEDRUSTLE: Array<SoundEvent> = soundArray("MISC_BEDRUSTLE", 2)
    @JvmField val MISC_SLAP: Array<SoundEvent> = soundArray("MISC_SLAP", 2)
    @JvmField val MISC_TOUCH: Array<SoundEvent> = soundArray("MISC_TOUCH", 2)
    @JvmField val MISC_POUNDING: Array<SoundEvent> = soundArray("MISC_POUNDING", 35)
    @JvmField val MISC_SMALLINSERTS: Array<SoundEvent> = soundArray("MISC_SMALLINSERTS", 5)
    @JvmField val MISC_INSERTS: Array<SoundEvent> = soundArray("MISC_INSERTS", 5)
    @JvmField val MISC_CUMINFLATION: Array<SoundEvent> = soundArray("MISC_CUMINFLATION", 1)
    @JvmField val MISC_SCREAM: Array<SoundEvent> = soundArray("MISC_SCREAM", 2)
    @JvmField val MISC_FART: Array<SoundEvent> = soundArray("MISC_FART", 3)
    @JvmField val MISC_JUMP: Array<SoundEvent> = soundArray("MISC_JUMP", 1)
    @JvmField val MISC_EAT: Array<SoundEvent> = soundArray("MISC_EAT", 3)
    @JvmField val MISC_SLIDE: Array<SoundEvent> = soundArray("MISC_SLIDE", 7)
    @JvmField val GIRLS_JENNY_AFTERSESSIONMOAN: Array<SoundEvent> = soundArray("GIRLS_JENNY_AFTERSESSIONMOAN", 5)
    @JvmField val GIRLS_JENNY_AHH: Array<SoundEvent> = soundArray("GIRLS_JENNY_AHH", 10)
    @JvmField val GIRLS_JENNY_BJMOAN: Array<SoundEvent> = soundArray("GIRLS_JENNY_BJMOAN", 13)
    @JvmField val GIRLS_JENNY_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_JENNY_GIGGLE", 5)
    @JvmField val GIRLS_JENNY_HAPPYOH: Array<SoundEvent> = soundArray("GIRLS_JENNY_HAPPYOH", 3)
    @JvmField val GIRLS_JENNY_HEAVYBREATHING: Array<SoundEvent> = soundArray("GIRLS_JENNY_HEAVYBREATHING", 8)
    @JvmField val GIRLS_JENNY_HMPH: Array<SoundEvent> = soundArray("GIRLS_JENNY_HMPH", 5)
    @JvmField val GIRLS_JENNY_HUH: Array<SoundEvent> = soundArray("GIRLS_JENNY_HUH", 2)
    @JvmField val GIRLS_JENNY_LIGHTBREATHING: Array<SoundEvent> = soundArray("GIRLS_JENNY_LIGHTBREATHING", 12)
    @JvmField val GIRLS_JENNY_LIPSOUND: Array<SoundEvent> = soundArray("GIRLS_JENNY_LIPSOUND", 10)
    @JvmField val GIRLS_JENNY_MMM: Array<SoundEvent> = soundArray("GIRLS_JENNY_MMM", 9)
    @JvmField val GIRLS_JENNY_MOAN: Array<SoundEvent> = soundArray("GIRLS_JENNY_MOAN", 8)
    @JvmField val GIRLS_JENNY_SADOH: Array<SoundEvent> = soundArray("GIRLS_JENNY_SADOH", 2)
    @JvmField val GIRLS_JENNY_SIGH: Array<SoundEvent> = soundArray("GIRLS_JENNY_SIGH", 2)
    @JvmField val GIRLS_ELLIE_AFTERSESSIONMOAN: Array<SoundEvent> = soundArray("GIRLS_ELLIE_AFTERSESSIONMOAN", 5)
    @JvmField val GIRLS_ELLIE_AHH: Array<SoundEvent> = soundArray("GIRLS_ELLIE_AHH", 10)
    @JvmField val GIRLS_ELLIE_BJMOAN: Array<SoundEvent> = soundArray("GIRLS_ELLIE_BJMOAN", 13)
    @JvmField val GIRLS_ELLIE_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_ELLIE_GIGGLE", 5)
    @JvmField val GIRLS_ELLIE_HAPPYOH: Array<SoundEvent> = soundArray("GIRLS_ELLIE_HAPPYOH", 3)
    @JvmField val GIRLS_ELLIE_HEAVYBREATHING: Array<SoundEvent> = soundArray("GIRLS_ELLIE_HEAVYBREATHING", 8)
    @JvmField val GIRLS_ELLIE_HMPH: Array<SoundEvent> = soundArray("GIRLS_ELLIE_HMPH", 4)
    @JvmField val GIRLS_ELLIE_HUH: Array<SoundEvent> = soundArray("GIRLS_ELLIE_HUH", 2)
    @JvmField val GIRLS_ELLIE_LIGHTBREATHING: Array<SoundEvent> = soundArray("GIRLS_ELLIE_LIGHTBREATHING", 8)
    @JvmField val GIRLS_ELLIE_LIPSOUND: Array<SoundEvent> = soundArray("GIRLS_ELLIE_LIPSOUND", 10)
    @JvmField val GIRLS_ELLIE_MMM: Array<SoundEvent> = soundArray("GIRLS_ELLIE_MMM", 9)
    @JvmField val GIRLS_ELLIE_MOAN: Array<SoundEvent> = soundArray("GIRLS_ELLIE_MOAN", 9)
    @JvmField val GIRLS_ELLIE_SADOH: Array<SoundEvent> = soundArray("GIRLS_ELLIE_SADOH", 2)
    @JvmField val GIRLS_ELLIE_SIGH: Array<SoundEvent> = soundArray("GIRLS_ELLIE_SIGH", 2)
    @JvmField val GIRLS_ELLIE_COMETOMOMMY: Array<SoundEvent> = soundArray("GIRLS_ELLIE_COMETOMOMMY", 2)
    @JvmField val GIRLS_ELLIE_GOODBOY: Array<SoundEvent> = soundArray("GIRLS_ELLIE_GOODBOY", 2)
    @JvmField val GIRLS_ELLIE_MOMMYHORNY: Array<SoundEvent> = soundArray("GIRLS_ELLIE_MOMMYHORNY", 2)
    @JvmField val GIRLS_BIA_AHH: Array<SoundEvent> = soundArray("GIRLS_BIA_AHH", 8)
    @JvmField val GIRLS_BIA_BJMOAN: Array<SoundEvent> = soundArray("GIRLS_BIA_BJMOAN", 5)
    @JvmField val GIRLS_BIA_BREATH: Array<SoundEvent> = soundArray("GIRLS_BIA_BREATH", 4)
    @JvmField val GIRLS_BIA_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_BIA_GIGGLE", 3)
    @JvmField val GIRLS_BIA_HEY: Array<SoundEvent> = soundArray("GIRLS_BIA_HEY", 4)
    @JvmField val GIRLS_BIA_HUH: Array<SoundEvent> = soundArray("GIRLS_BIA_HUH", 3)
    @JvmField val GIRLS_BIA_MMM: Array<SoundEvent> = soundArray("GIRLS_BIA_MMM", 8)
    @JvmField val GIRLS_LUNA_AHH: Array<SoundEvent> = soundArray("GIRLS_LUNA_AHH", 18)
    @JvmField val GIRLS_LUNA_CUTENYA: Array<SoundEvent> = soundArray("GIRLS_LUNA_CUTENYA", 12)
    @JvmField val GIRLS_LUNA_HAPPYOH: Array<SoundEvent> = soundArray("GIRLS_LUNA_HAPPYOH", 8)
    @JvmField val GIRLS_LUNA_HMPH: Array<SoundEvent> = soundArray("GIRLS_LUNA_HMPH", 6)
    @JvmField val GIRLS_LUNA_HORNINYA: Array<SoundEvent> = soundArray("GIRLS_LUNA_HORNINYA", 10)
    @JvmField val GIRLS_LUNA_HUH: Array<SoundEvent> = soundArray("GIRLS_LUNA_HUH", 5)
    @JvmField val GIRLS_LUNA_LIGHTBREATHING: Array<SoundEvent> = soundArray("GIRLS_LUNA_LIGHTBREATHING", 25)
    @JvmField val GIRLS_LUNA_MMM: Array<SoundEvent> = soundArray("GIRLS_LUNA_MMM", 8)
    @JvmField val GIRLS_LUNA_MOAN: Array<SoundEvent> = soundArray("GIRLS_LUNA_MOAN", 10)
    @JvmField val GIRLS_LUNA_SADOH: Array<SoundEvent> = soundArray("GIRLS_LUNA_SADOH", 7)
    @JvmField val GIRLS_LUNA_SIGH: Array<SoundEvent> = soundArray("GIRLS_LUNA_SIGH", 8)
    @JvmField val GIRLS_LUNA_SINGING: Array<SoundEvent> = soundArray("GIRLS_LUNA_SINGING", 8)
    @JvmField val GIRLS_LUNA_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_LUNA_GIGGLE", 15)
    @JvmField val GIRLS_LUNA_OUU: Array<SoundEvent> = soundArray("GIRLS_LUNA_OUU", 13)
    @JvmField val GIRLS_LUNA_OWO: Array<SoundEvent> = soundArray("GIRLS_LUNA_OWO", 8)
    @JvmField val GIRLS_ALLIE_AFTERSESSIONMOAN: Array<SoundEvent> = soundArray("GIRLS_ALLIE_AFTERSESSIONMOAN", 4)
    @JvmField val GIRLS_ALLIE_AHH: Array<SoundEvent> = soundArray("GIRLS_ALLIE_AHH", 10)
    @JvmField val GIRLS_ALLIE_BJMOAN: Array<SoundEvent> = soundArray("GIRLS_ALLIE_BJMOAN", 14)
    @JvmField val GIRLS_ALLIE_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_ALLIE_GIGGLE", 5)
    @JvmField val GIRLS_ALLIE_HAPPYOH: Array<SoundEvent> = soundArray("GIRLS_ALLIE_HAPPYOH", 3)
    @JvmField val GIRLS_ALLIE_HEAVYBREATHING: Array<SoundEvent> = soundArray("GIRLS_ALLIE_HEAVYBREATHING", 8)
    @JvmField val GIRLS_ALLIE_HMPH: Array<SoundEvent> = soundArray("GIRLS_ALLIE_HMPH", 5)
    @JvmField val GIRLS_ALLIE_HUH: Array<SoundEvent> = soundArray("GIRLS_ALLIE_HUH", 2)
    @JvmField val GIRLS_ALLIE_LIGHTBREATHING: Array<SoundEvent> = soundArray("GIRLS_ALLIE_LIGHTBREATHING", 11)
    @JvmField val GIRLS_ALLIE_LIPSOUND: Array<SoundEvent> = soundArray("GIRLS_ALLIE_LIPSOUND", 14)
    @JvmField val GIRLS_ALLIE_MMM: Array<SoundEvent> = soundArray("GIRLS_ALLIE_MMM", 10)
    @JvmField val GIRLS_ALLIE_MOAN: Array<SoundEvent> = soundArray("GIRLS_ALLIE_MOAN", 8)
    @JvmField val GIRLS_ALLIE_SADOH: Array<SoundEvent> = soundArray("GIRLS_ALLIE_SADOH", 2)
    @JvmField val GIRLS_ALLIE_SIGH: Array<SoundEvent> = soundArray("GIRLS_ALLIE_SIGH", 2)
    @JvmField val GIRLS_ALLIE_SCAWY: Array<SoundEvent> = soundArray("GIRLS_ALLIE_SCAWY", 3)
    @JvmField val GIRLS_KOBOLD_BJMOAN: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_BJMOAN", 10)
    @JvmField val GIRLS_KOBOLD_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_GIGGLE", 4)
    @JvmField val GIRLS_KOBOLD_HAA: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_HAA", 7)
    @JvmField val GIRLS_KOBOLD_HEYMASTER: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_HEYMASTER", 6)
    @JvmField val GIRLS_KOBOLD_INTERESTED: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_INTERESTED", 3)
    @JvmField val GIRLS_KOBOLD_LIGHTBREATHING: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_LIGHTBREATHING", 12)
    @JvmField val GIRLS_KOBOLD_MASTER: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_MASTER", 6)
    @JvmField val GIRLS_KOBOLD_MOAN: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_MOAN", 10)
    @JvmField val GIRLS_KOBOLD_ORGASM: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_ORGASM", 4)
    @JvmField val GIRLS_KOBOLD_SAD: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_SAD", 3)
    @JvmField val GIRLS_KOBOLD_YEP: Array<SoundEvent> = soundArray("GIRLS_KOBOLD_YEP", 7)
    @JvmField val MISC_FLAP: Array<SoundEvent> = soundArray("MISC_FLAP", 4)
    @JvmField val MISC_SHATTER: Array<SoundEvent> = soundArray("MISC_SHATTER", 1)
    @JvmField val MISC_WEOWEO: Array<SoundEvent> = soundArray("MISC_WEOWEO", 4)
    @JvmField val MISC_BEEW: Array<SoundEvent> = soundArray("MISC_BEEW", 3)
    @JvmField val MISC_CLAP: Array<SoundEvent> = soundArray("MISC_CLAP", 1)
    @JvmField val GIRLS_GALATH_AHH: Array<SoundEvent> = soundArray("GIRLS_GALATH_AHH", 8)
    @JvmField val GIRLS_GALATH_BREATHING: Array<SoundEvent> = soundArray("GIRLS_GALATH_BREATHING", 7)
    @JvmField val GIRLS_GALATH_DIALOG: Array<SoundEvent> = soundArray("GIRLS_GALATH_DIALOG", 6)
    @JvmField val GIRLS_GALATH_GIGGLE: Array<SoundEvent> = soundArray("GIRLS_GALATH_GIGGLE", 4)
    @JvmField val GIRLS_GALATH_HMPH: Array<SoundEvent> = soundArray("GIRLS_GALATH_HMPH", 3)
    @JvmField val GIRLS_GALATH_HUH: Array<SoundEvent> = soundArray("GIRLS_GALATH_HUH", 3)
    @JvmField val GIRLS_GALATH_LIGHTCHARGE: Array<SoundEvent> = soundArray("GIRLS_GALATH_LIGHTCHARGE", 5)
    @JvmField val GIRLS_GALATH_MOAN: Array<SoundEvent> = soundArray("GIRLS_GALATH_MOAN", 8)
    @JvmField val GIRLS_GALATH_STRONGCHARGE: Array<SoundEvent> = soundArray("GIRLS_GALATH_STRONGCHARGE", 4)
    @JvmField val GIRLS_GALATH_UUH: Array<SoundEvent> = soundArray("GIRLS_GALATH_UUH", 7)
    @JvmField val GIRLS_GALATH_ORGASM: Array<SoundEvent> = soundArray("GIRLS_GALATH_ORGASM", 5)
    @JvmField val GIRLS_GALATH_AAA: Array<SoundEvent> = soundArray("GIRLS_GALATH_AAA", 2)
    @JvmField val MISC_PYRO: Array<SoundEvent> = soundArray("MISC_PYRO", 1)
    @JvmField val lastRandomSound: HashMap<SoundEvent, Int> = HashMap()

    /**
     * Kept for call sites. Registration now happens through `ModSounds`
     * (DeferredRegister) which registers [ALL_SOUND_EVENTS]; this is a no-op.
     */
    @JvmStatic
    fun registerSounds() {
    }

    /**
     * Creates (does not register) a sound event `sexmod:<soundName>`. Kept for
     * call sites; registration goes through `ModSounds`.
     */
    @JvmStatic
    fun registerSounds(soundName: String): SoundEvent =
        ALL_SOUND_EVENTS[soundName] ?: SoundEvent.createVariableRangeEvent(Main.rl(soundName))

    @JvmStatic
    fun randomSound(sounds: Array<SoundEvent>): SoundEvent {
        lastRandomSound.putIfAbsent(sounds[0], -69)
        var attempts = 0

        var index: Int
        do {
            index = Reference.RANDOM.nextInt(sounds.size)
        } while (++attempts < 10 && index == lastRandomSound[sounds[0]])

        lastRandomSound.replace(sounds[0], index)
        return sounds[index]
    }
}
