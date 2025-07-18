package com.qytech.audioplayer.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SuspendLazy<T>(private val initializer: suspend () -> T) {
    private val mutex = Mutex()

    @Volatile
    private var _value: Any? = UNINITIALIZED

    suspend fun get(): T {
        val current = _value
        if (current !== UNINITIALIZED) {
            @Suppress("UNCHECKED_CAST")
            return current as T
        }

        return mutex.withLock {
            val doubleCheck = _value
            if (doubleCheck !== UNINITIALIZED) {
                @Suppress("UNCHECKED_CAST")
                return@withLock doubleCheck as T
            }

            val initialized = initializer()
            _value = initialized
            initialized
        }
    }

    companion object {
        private object UNINITIALIZED
    }
}
