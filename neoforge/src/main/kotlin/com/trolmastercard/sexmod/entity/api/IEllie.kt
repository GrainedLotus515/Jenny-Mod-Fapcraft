package com.trolmastercard.sexmod.entity.api

/**
 * <b>Role.</b> Marker interface for the girls with a dismount/scene-entry
 * hook (`EllieEntity`, and implicitly the other NPC girls that react to
 * `KoboldStatePacket`). `KoboldStatePacket.Handler.sendState`
 * calls [setDismounted] on the target girl to start the
 * carry/scene-entry flow — do not rename or re-signature this method, the
 * packet handler type-checks against [IEllie].
 */
interface IEllie {
    fun setDismounted()
}
