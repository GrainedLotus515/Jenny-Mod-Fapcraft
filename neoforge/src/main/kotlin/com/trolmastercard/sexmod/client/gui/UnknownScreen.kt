package com.trolmastercard.sexmod.client.gui

/**
 * Plain RGBA color holder used by the mod's custom model/part rendering code
 * (e.g. tinting custom parts on the [ClothingScreen]). No logic — just a
 * value object with a public color tuple.
 *
 * Port note: despite its package this class mentions no client types, so
 * common code (`RotationHelper`) may use it.
 */
open class UnknownScreen(
    @JvmField var red: Int,
    @JvmField var green: Int,
    @JvmField var blue: Int,
    @JvmField var alpha: Int,
)
