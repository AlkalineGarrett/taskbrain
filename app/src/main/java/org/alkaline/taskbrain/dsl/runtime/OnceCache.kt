package org.alkaline.taskbrain.dsl.runtime

import org.alkaline.taskbrain.dsl.runtime.values.DslValue

/**
 * Cache for `once[...]` expression results.
 *
 * The cache stores results by a key derived from the expression text/hash.
 * Results persist until the directive text changes (which changes the key).
 */
interface OnceCache {
    /**
     * Get a cached value by key.
     * @return The cached value, or null if not found
     */
    fun get(key: String): DslValue?

    /**
     * Store a value in the cache.
     */
    fun put(key: String, value: DslValue)

    /**
     * Check if a key exists in the cache.
     */
    fun contains(key: String): Boolean
}

/**
 * In-memory implementation of OnceCache.
 * Values persist for the lifetime of the cache instance.
 */
class InMemoryOnceCache : OnceCache {
    private val cache = mutableMapOf<String, DslValue>()

    override fun get(key: String): DslValue? = cache[key]

    override fun put(key: String, value: DslValue) {
        cache[key] = value
    }

    override fun contains(key: String): Boolean = cache.containsKey(key)
}
