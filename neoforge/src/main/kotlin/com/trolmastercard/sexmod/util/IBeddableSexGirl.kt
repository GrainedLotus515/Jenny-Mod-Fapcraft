package com.trolmastercard.sexmod.util

/**
 * <b>Role.</b> Marker interface for girls that support the bed-scene walk-up:
 * `goToSexBed()` moves the girl to her bed before the scene starts.
 * Implemented by the beddable NPC girls (Jenny, Bia, ...); invoked by the
 * `SendGirlToSexPacket` flow. Girls that do not implement this interface
 * play their scenes in place.
 */
interface IBeddableSexGirl {
    fun goToSexBed()
}
