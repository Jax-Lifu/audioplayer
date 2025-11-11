package com.qytech.audioplayer.utils

import com.qytech.audioplayer.BuildConfig
import timber.log.Timber


object Logger {

    private val ENABLE_LOG = BuildConfig.DEBUG

    fun d(msg: String) {
        if (ENABLE_LOG) {
            Timber.d(msg)
        }
    }

    fun i(msg: String) {
        if (ENABLE_LOG) {
            Timber.i(msg)
        }
    }

    fun w(msg: String) {
        if (ENABLE_LOG) {
            Timber.w(msg)
        }
    }

    fun e(msg: String) {
        Timber.e(msg)
    }

    fun e(t: Throwable? = null, msg: String) {
        Timber.e(t, msg)
    }


    fun v(msg: String) {
        if (ENABLE_LOG) {
            Timber.v(msg)
        }
    }

    fun f(msg: String, t: Throwable? = null) {
        if (ENABLE_LOG) {
            Timber.wtf(t, msg)
        }
    }
}
