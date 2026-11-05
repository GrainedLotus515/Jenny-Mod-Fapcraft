package com.trolmastercard.sexmod.client.model.api

/**
 * Model metadata contract for girl models: named bone lists for the outfit
 * pieces (head armor, attachments, top/bottom armor, shoes, ...). Defaults to
 * empty; models that support the customization/outfit system override the
 * relevant accessors with the actual bone names.
 */
interface IGirlModelInfo {
    fun HeadArmor(): Array<String> = arrayOf()

    fun Attachments(): Array<String> = arrayOf()

    fun TopArmor(): Array<String> = arrayOf()

    fun Top(): Array<String> = arrayOf()

    fun BottomArmor(): Array<String> = arrayOf()

    fun Bottom(): Array<String> = arrayOf()

    fun ShoesArmor(): Array<String> = arrayOf()

    fun Shoes(): Array<String> = arrayOf()
}
