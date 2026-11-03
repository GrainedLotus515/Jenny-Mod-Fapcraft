package com.trolmastercard.sexmod.util

/**
 * **Role.** Minimal two-way map (K->V and V->K) with consistent mutation
 * invariants: every [put] keeps both directions in sync, [removeByKey]
 * removes both entries. Used by [GirlSavedData] for player<->galath
 * ownership pairs.
 *
 * **Invariant.** [put] with an existing value silently drops the old
 * reverse mapping (the value is re-bound to the new key) — callers relying on
 * multi-mapping must not use this class.
 */
class BiDirectionalMap<K, V> {
    private val byKey: HashMap<K, V> = HashMap()
    private val byValue: HashMap<V, K> = HashMap()

    fun put(key: K, value: V) {
        val oldValue = this.byKey.put(key, value)
        this.byValue.remove(oldValue)
        this.byValue[value] = key
    }

    fun getByKey(key: K): V? = this.byKey[key]

    fun getByValue(value: V): K? = this.byValue[value]

    fun size(): Int = this.byKey.size

    fun removeByKey(key: K) {
        val removedValue = this.byKey[key]
        if (removedValue != null) {
            this.byKey.remove(key)
            this.byValue.remove(removedValue)
        }
    }

    fun entrySet(): MutableSet<MutableMap.MutableEntry<K, V>> = this.byKey.entries

    fun keySet(): MutableSet<K> = this.byKey.keys

    fun valueSet(): MutableSet<V> = this.byValue.keys

    fun clear() {
        this.byValue.clear()
        this.byKey.clear()
    }
}
