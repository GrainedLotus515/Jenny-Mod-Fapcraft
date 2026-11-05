package com.trolmastercard.sexmod.entity.api

/**
 * <b>Role.</b> Marker contract for the kobold girls: [isBlockedByCeiling]
 * tells the AI/pathing whether a ceiling blocks the kobold's head space
 * (used by the kobold NPC tasks — see `KoboldTask`).
 */
interface IKobold {
    fun isBlockedByCeiling(): Boolean
}
