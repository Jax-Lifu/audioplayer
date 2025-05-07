package com.qytech.audioplayer.decrypted

import android.annotation.SuppressLint
import androidx.media3.datasource.DataSource

class FlacAesDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val securityKey: String,
    private val initVector: String
) : DataSource.Factory {

    @SuppressLint("UnsafeOptInUsageError")
    override fun createDataSource(): DataSource {
        return FlacAesDataSource(
            upstreamFactory.createDataSource(),
            securityKey,
            initVector
        )
    }
}