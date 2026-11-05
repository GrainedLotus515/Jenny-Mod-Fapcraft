package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * <b>Role.</b> Global girl UUID registry (girlId -> entity instance). Kept in
 * sync by [BaseGirlEntity] (register on spawn, unregister on removal) and
 * cleared on world close. Used by `setLocallyRegistered` and fast UUID
 * lookups that must not scan the entity list.
 *
 * <b>Pitfall.</b> Entries must be removed when girls despawn or stale references
 * keep dead entities alive. Do not use it across worlds. The map is concurrent
 * because preview girls are (un)registered on the render thread while the
 * server tick reads it.
 */
object GirlRegistry {
    @JvmField
    var girlsById: ConcurrentHashMap<UUID, BaseGirlEntity> = ConcurrentHashMap()

    @JvmStatic
    fun registerGirl(girl: BaseGirlEntity) {
        girlsById[girl.getGirlId()] = girl
    }

    @JvmStatic
    fun unregisterGirl(girl: BaseGirlEntity) {
        girlsById.remove(girl.getGirlId())
    }

    @JvmStatic
    fun clearAll() {
        girlsById.clear()
    }

    @JvmStatic
    fun getGirl(uuid: UUID): BaseGirlEntity? = girlsById[uuid]
}
