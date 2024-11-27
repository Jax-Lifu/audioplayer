package com.qytech.audioplayer.extension

// Map 扩展函数：更新键值，如果键存在则合并数据，不存在则设置新值
fun MutableMap<String, String>.updateOrAdd(key: String, newValue: String) {
    this[key] = this[key]?.let { "$it,$newValue" } ?: newValue
}