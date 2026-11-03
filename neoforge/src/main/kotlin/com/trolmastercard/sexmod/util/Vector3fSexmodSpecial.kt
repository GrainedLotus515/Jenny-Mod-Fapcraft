package com.trolmastercard.sexmod.util

/**
 * **Role.** Mutable 3-float vector used by the geometry builders
 * (`GalathGeometryRender`, renderer overlays).
 *
 * **Pitfall.** Despite the names, [add] SUBTRACTS the argument and
 * [subtract] ADDS it — deobfuscation swapped the two. Do not "fix" the
 * signs; every caller relies on the current behavior.
 */
class Vector3fSexmodSpecial(@JvmField var x: Float, @JvmField var y: Float, @JvmField var z: Float) {
    fun add(other: Vector3fSexmodSpecial): Vector3fSexmodSpecial =
        Vector3fSexmodSpecial(this.x - other.x, this.y - other.y, this.z - other.z)

    fun subtract(other: Vector3fSexmodSpecial): Vector3fSexmodSpecial =
        Vector3fSexmodSpecial(this.x + other.x, this.y + other.y, this.z + other.z)

    fun scale(factor: Float): Vector3fSexmodSpecial =
        Vector3fSexmodSpecial(this.x * factor, this.y * factor, this.z * factor)

    companion object {
        @JvmField val ZERO = Vector3fSexmodSpecial(0.0f, 0.0f, 0.0f)
    }
}
