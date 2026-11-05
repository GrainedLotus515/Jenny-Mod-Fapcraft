package com.trolmastercard.sexmod.client.model.api

import net.minecraft.client.model.geom.ModelPart

/**
 * Minimal contract for classes exposing a vanilla [ModelPart] (used
 * by the vanilla-model rendering paths for NPCs like the slime).
 *
 * Port note: the interface itself is referenced from COMMON code
 * (`AbstractPlayerGirlEntity.getHandModel`); only implementations are client-only.
 */
interface IVanillaModel {
    fun getModel(): ModelPart
}
