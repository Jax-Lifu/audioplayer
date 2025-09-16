package com.qytech.audioplayer.utils


/**
 * 通过反射调用 Android 系统隐藏的 SystemProperties
 * 可用于读取和设置系统属性
 *
 * 注意：
 * - 普通应用只能读取属性，写入属性需要系统权限
 * - Android P 之后反射可能被限制，需要绕过 hidden API
 */
object SystemPropUtil {

    private var systemPropertiesClass: Class<*>? = null
    private var getMethod: java.lang.reflect.Method? = null
    private var getIntMethod: java.lang.reflect.Method? = null
    private var getBooleanMethod: java.lang.reflect.Method? = null
    private var setMethod: java.lang.reflect.Method? = null

    init {
        try {
            systemPropertiesClass = Class.forName("android.os.SystemProperties")

            getMethod = systemPropertiesClass?.getMethod("get", String::class.java)
            getIntMethod = systemPropertiesClass?.getMethod(
                "getInt",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            getBooleanMethod = systemPropertiesClass?.getMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            setMethod =
                systemPropertiesClass?.getMethod("set", String::class.java, String::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取系统属性 (String)
     */
    fun get(key: String, def: String = ""): String {
        return try {
            getMethod?.invoke(null, key) as? String ?: def
        } catch (e: Exception) {
            e.printStackTrace()
            def
        }
    }

    /**
     * 读取系统属性 (Int)，带默认值
     */
    fun getInt(key: String, def: Int = 0): Int {
        return try {
            getIntMethod?.invoke(null, key, def) as? Int ?: def
        } catch (e: Exception) {
            e.printStackTrace()
            def
        }
    }

    /**
     * 读取系统属性 (Boolean)，带默认值
     */
    fun getBoolean(key: String, def: Boolean = false): Boolean {
        return try {
            val value = get(key, if (def) "1" else "0") // 先拿字符串
            when (value.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> def
            }
        } catch (e: Exception) {
            e.printStackTrace()
            def
        }
    }

    /**
     * 设置系统属性 (需要 system/priv-app 权限)
     */
    fun set(key: String, value: String) {
        try {
            setMethod?.invoke(null, key, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
