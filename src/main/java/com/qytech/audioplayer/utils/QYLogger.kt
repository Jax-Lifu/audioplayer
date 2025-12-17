package com.qytech.audioplayer.utils

import com.qytech.audioplayer.BuildConfig
import timber.log.Timber

internal object QYLogger {

    private val DEBUG = /*if (true) true else*/ BuildConfig.DEBUG

    inline fun d(message: () -> String) {
        if (DEBUG) {
            Timber.d(message())
        }
    }

    fun d(message: String, vararg args: Any?) {
        if (DEBUG) {
            Timber.d(message, *args)
        }
    }

    fun i(message: String, vararg args: Any?) {
        if (DEBUG) {
            Timber.i(message, *args)
        }
    }

    fun w(message: String, vararg args: Any?) {
        if (DEBUG) {
            Timber.w(message, *args)
        }
    }

    fun e(t: Throwable, message: String, vararg args: Any?) {
        if (DEBUG) {
            Timber.e(t, message, *args)
        }
    }

    fun e(message: String, vararg args: Any?) {
        if (DEBUG) {
            Timber.e(message, *args)
        }
    }

    fun e(t: Throwable) {
        if (DEBUG) {
            Timber.e(t)
        }
    }
}
