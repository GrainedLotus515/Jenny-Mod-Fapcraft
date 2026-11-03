package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.BuildInfo
import net.minecraft.world.phys.Vec3
import java.util.Random

/**
 * **Role.** Global mod constants: ids, proxy class names, entity ids,
 * [RANDOM], the shared camera-position pair used by
 * [StructureMarkerRenderer] and render interpolation. Static holder.
 *
 * **Pitfall.** `cameraPosCurrent/Previous` are written by
 * `StructureMarkerRenderer.onClientTick` and read by renderers — the
 * tick-order (previous = current, then current = player pos) must not be
 * reversed or the interpolation jitters.
 */
object Reference {
    const val MOD_ID = "sexmod"
    const val MOD_NAME = "Fapcraft"
    @JvmField val MOD_VERSION: String = BuildInfo.VERSION
    const val CLIENT_PROXY = "com.trolmastercard.sexmod.ClientProxy"
    const val COMMON_PROXY = "com.trolmastercard.sexmod.CommonProxy"
    @JvmField val RANDOM: Random = Random()
    @JvmField var EDITOR_ID_COUNTER = 0
    @JvmField var BUTTON_ID_COUNTER = 0
    const val KOBOLD_EGG_ENTITY_ID = 4674237
    const val CUSTOM_MODEL_ENTITY_ID = 6281823
    @JvmField var cameraPosCurrent: Vec3 = Vec3.ZERO
    @JvmField var cameraPosPrevious: Vec3 = Vec3.ZERO
}
