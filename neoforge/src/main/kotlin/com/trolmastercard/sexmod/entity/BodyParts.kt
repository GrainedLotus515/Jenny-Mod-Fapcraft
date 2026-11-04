package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.renderer.api.IGirlRenderer
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * **Role.** Static helpers for the girl renderers that compute the world
 * offset of "custom" bones (boobs/booty/vagina/fuckhole) so render layers and
 * effects can target them. Per-renderer bone classification is cached in
 * `CUSTOM_BONE_CACHE` to keep the parent-bone walk cheap.
 *
 * **Pitfalls.** [offsetBonePosition] is
 * misnamed by the deobf — it does NOT add the offset; it progress-lerps the
 * position toward the skin-color vector (`SKIN_COLOR`/
 * `SKIN_COLOR_ALT`) by an eased dot-product factor (0.1 max). The
 * renderer's look vector must be refreshed via
 * [updateBoneOffset] every frame before
 * [getBoneWorldPosition].
 *
 * Port note: render-only (CLIENT) despite its package; it mentions only the
 * client-only [IGirlRenderer] and is never loaded on a dedicated server.
 */
object BodyParts {
    @JvmField val SKIN_COLOR: Vec3 = Vec3(0.95, 0.65, 0.85)
    @JvmField val SKIN_COLOR_ALT: Vec3 = Vec3(0.0, 0.2, 0.3)
    const val ALPHA: Float = 0.1f

    @JvmField
    val CUSTOM_PART_BONES: HashSet<String> = hashSetOf("boobs", "booty", "vagina", "fuckhole")

    @JvmField
    var CUSTOM_BONE_CACHE: HashMap<IGirlRenderer, HashMap<String, Boolean>> = HashMap()

    @JvmField
    var OFFSET_VEC: Vec3? = null

    @JvmStatic
    fun isCustomBone(renderer: IGirlRenderer, bone: GeoBone): Boolean {
        val cache = CUSTOM_BONE_CACHE[renderer]
        if (cache == null) {
            val newCache = HashMap<String, Boolean>()
            val custom = renderer.hasParentBone(renderer.getBlacklistedBones(), bone)
            newCache[bone.name] = custom
            CUSTOM_BONE_CACHE[renderer] = newCache
            return custom
        } else {
            var cached = cache[bone.name]
            if (cached == null) {
                cached = renderer.hasParentBone(renderer.getBlacklistedBones(), bone)
                cache[bone.name] = cached
                CUSTOM_BONE_CACHE[renderer] = cache
                return cached
            } else {
                return cached
            }
        }
    }

    @JvmStatic
    fun getBoneWorldPosition(renderer: IGirlRenderer, bone: GeoBone, worldPos: Vec3, rotation: Vector3f): Vec3 {
        return if (!isCustomBone(renderer, bone)) worldPos else offsetBonePosition(worldPos, rotation, OFFSET_VEC!!) // java-npe: updateBoneOffset runs first
    }

    @JvmStatic
    fun offsetBonePosition(worldPos: Vec3, rotation: Vector3f, offset: Vec3): Vec3 {
        val dot = VectorMath.dotProduct(rotation, offset)
        var fade = RotationHelper.easeInOutQuad(Math.abs(dot))
        fade *= 0.1f.toDouble()
        return RotationHelper.lerpVec3dDouble(worldPos, if (dot > 0.0) SKIN_COLOR else SKIN_COLOR_ALT, fade)
    }

    @JvmStatic
    fun updateBoneOffset(entity: LivingEntity, partialTicks: Float) {
        OFFSET_VEC = WorldUtils.getEntityLookVector(entity, partialTicks)
    }

    @JvmStatic
    fun updateCustomBones(bones: Collection<GeoBone>, blacklisted: HashSet<String>, renderer: IGirlRenderer) {
        if (CUSTOM_BONE_CACHE[renderer] == null) {
            val customBones = HashMap<String, Boolean>()

            for (bone in bones) {
                customBones[bone.name] = renderer.hasParentBone(blacklisted, bone)
            }

            CUSTOM_BONE_CACHE[renderer] = customBones
        }
    }
}
