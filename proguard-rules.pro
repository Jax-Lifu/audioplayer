# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontwarn java.lang.invoke.StringConcatFactory


###############################
## Kotlin / JVM 基础
###############################
# 保留 Kotlin 反射需要的元数据
-keep class kotlin.Metadata { *; }

# 保留内部类、匿名类、合成类
-keepattributes InnerClasses,EnclosingMethod,Signature,Exceptions,SourceFile,LineNumberTable

# 保留 Kotlin 编译器生成的合成类
-keep class **$$ExternalSyntheticOutline* { *; }
-keepnames class **$$ExternalSyntheticOutline* { *; }

###############################
## 你的应用包
###############################
# 保留整个 com.qytech 包（类名 + 成员）
-keep class com.qytech.** { *; }

# 如果只想保留类名（方法/字段允许优化）
# -keepnames class com.qytech.**

###############################
## Jetpack Compose
###############################
# Compose runtime 需要的类
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 保留 Kotlin lambda（Compose 很多 UI 是 lambda）
-keepclassmembers class * {
    <methods>;
}

###############################
## 序列化 / 反射
###############################
# kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Gson / Moshi 如果用到
-keep class com.google.gson.** { *; }
-keep class com.squareup.moshi.** { *; }

###############################
## 常见第三方库（可选）
###############################
# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

###############################
## 日志可读性优化
###############################
# 保留类名、方法名映射，方便崩溃日志分析
-keepattributes SourceFile,LineNumberTable

-keep class com.google.common.reflect.** {*;}
