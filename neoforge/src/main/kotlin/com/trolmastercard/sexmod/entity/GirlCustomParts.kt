package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.ServerWhitelistManager
import java.util.AbstractMap.SimpleEntry
import java.util.UUID
import net.neoforged.fml.loading.FMLEnvironment

/**
 * Custom body-part lists and the `customPartsData` table that drives the
 * sliders: id list (APPARANCE_DNA for NPC-only girls), per-part names and
 * per-part current values.
 *
 * Extracted verbatim from [BaseGirlEntity]; the entity keeps its methods
 * as delegates and its `customPartsData` field, which subclasses read
 * directly. `getBasePartIdList`/`getCustomPartIdList` are called
 * back through the girl so the per-girl overrides still dispatch.
 *
 * Port note: `Main.proxy instanceof ClientProxy` (a client-only type) is
 * `FMLEnvironment.dist.isClient`, which is the same test: the proxy was
 * chosen by physical side.
 */
object GirlCustomParts {
    @JvmStatic
    fun isPartEnabled(girl: BaseGirlEntity, index: Int): Boolean {
        val parts = girl.getCustomPartIdList()
        return if (parts.size - 1 < index) false else parts[index] == 101
    }

    @JvmStatic
    fun setCustomPartList(girl: BaseGirlEntity, parts: List<Int>) {
        if (girl is AbstractNpcOnlyEntity || girl is AbstractKoboldPlayerEntity) {
            val builder = StringBuilder()

            for (partId in parts) {
                AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
            }

            girl.entityDataManager.set(AbstractNpcOnlyEntity.APPEARANCE_DNA, builder.toString())
        }
    }

    @JvmStatic
    fun getCustomPartListCode(girl: BaseGirlEntity): String {
        return if (girl !is AbstractNpcOnlyEntity && girl !is AbstractKoboldPlayerEntity) {
            ""
        } else {
            girl.entityDataManager.get(AbstractNpcOnlyEntity.APPEARANCE_DNA)
        }
    }

    @JvmStatic
    fun setCustomPartListCode(girl: BaseGirlEntity, code: String) {
        if (girl is AbstractNpcOnlyEntity || girl is AbstractKoboldPlayerEntity) {
            girl.entityDataManager.set(AbstractNpcOnlyEntity.APPEARANCE_DNA, code)
        }
    }

    @JvmStatic
    fun getAllPartIdsForGirl(uuid: UUID): List<Int> {
        val girl = if (FMLEnvironment.dist.isClient) BaseGirlEntity.getClientGirlEntity(uuid) else BaseGirlEntity.getServerGirlEntity(uuid)
        // NULL-GUARD: for a freshly constructed / unregistered entity the registry
        // lookup returns null — the caller falls back to this-girl data instead of NPE.
        if (girl == null) return ArrayList()
        val parts = ArrayList<Int>(girl.getBasePartIdList())
        if (girl is AbstractNpcOnlyEntity || girl is AbstractKoboldPlayerEntity) {
            val dna: String? = girl.getEntityData().get(AbstractNpcOnlyEntity.APPEARANCE_DNA)
            parts.addAll(BaseGirlEntity.decodePartIdList(dna ?: ""))
        }

        return parts
    }

    @JvmStatic
    fun buildCustomPartsData(girl: BaseGirlEntity, uuid: UUID): List<Map.Entry<BoneType, Map.Entry<List<String>, Int>>> {
        val existing = girl.customPartsData
        if (existing != null) {
            return existing
        }

        val parts = girl.getCustomPartIdList()
        if (parts.isEmpty()) {
            val empty = ArrayList<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>()
            girl.customPartsData = empty
            return empty
        }

        // Read the current values from THIS entity (works for preview girls that are
        // not in any registry). Fall back gracefully if allParts is short.
        val allParts: List<Int>? = currentPartValues(girl)
        val data = ArrayList<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>()
        for (i in parts.indices) {
            val cur = if (allParts != null && i < allParts.size) allParts[i] else 0
            data.add(SimpleEntry(BoneType.GIRL_SPECIFIC, SimpleEntry(GirlPartCodec.partNames(parts[i]), cur)))
        }

        girl.customPartsData = data
        return data
    }

    /** Current part values directly from the girl's own data (no registry needed). */
    private fun currentPartValues(girl: BaseGirlEntity): List<Int> {
        val out = ArrayList<Int>()
        try {
            out.addAll(girl.getBasePartIdList())
        } catch (t: Throwable) {
            // ignored: optional base part list; the appearance DNA below is the fallback
        }
        try {
            if (girl is AbstractNpcOnlyEntity || girl is AbstractKoboldPlayerEntity) {
                val dna: String? = girl.getEntityData().get(AbstractNpcOnlyEntity.APPEARANCE_DNA)
                out.addAll(BaseGirlEntity.decodePartIdList(dna ?: ""))
            }
        } catch (t: Throwable) {
            // ignored: undecodable appearance DNA; keep the parts collected so far
        }
        return out
    }

    @JvmStatic
    fun setCustomPartsData(girl: BaseGirlEntity, data: List<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>?) {
        girl.customPartsData = data
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun setCustomPartValue(girl: BaseGirlEntity, index: Int, value: Int) {
        val data = girl.customPartsData
        if (data != null) {
            if (data.size - 1 >= index) {
                val entry = data[index]
                (entry.value as MutableMap.MutableEntry<List<String>, Int>).setValue(value)
                (data as MutableList<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>)[index] = entry
            }
        }
    }

    /**
     * Removes server-whitelisted custom parts from the girl's model code and
     * persists the filtered set (SERVER side; respects ServerWhitelistManager).
     */
    @JvmStatic
    fun updateCustomModelParts(girl: BaseGirlEntity) {
        if (ServerWhitelistManager.isLoaded) {
            val parts = girl.getCustomPartsSet()
            val type = NpcType.getNpcType(girl)
            val allowed = HashSet<String>()
            val group = ServerWhitelistManager.getCurrentGroup()

            for (part in parts) {
                if ("" != ServerWhitelistManager.getPartName(part, group)) {
                    allowed.add(part)
                } else {
                    val allowedTypes = ServerWhitelistManager.getAllowedNpcTypes(part)
                    if (allowedTypes == null) {
                        allowed.add(part)
                    } else if (!allowedTypes.isEmpty() && !allowedTypes.contains(type)) {
                        allowed.add(part)
                    }
                }
            }

            if (!allowed.isEmpty()) {
                parts.removeAll(allowed)
                girl.setCustomModelCode(BaseGirlEntity.encodeCustomParts(parts))
            }
        }
    }
}
