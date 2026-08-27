# gomobile 生成的绑定类通过 JNI 从原生侧反查，混淆或裁剪会导致运行时崩溃
-keep class com.niceproxy.libnice.** { *; }
-keep class go.** { *; }

# kotlinx.serialization 生成的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.niceproxy.**$$serializer { *; }
-keepclassmembers class com.niceproxy.** {
    *** Companion;
    *** INSTANCE;
}

# OkHttp 在无 Conscrypt/BouncyCastle 时的可选依赖
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# snakeyaml 的 JavaBean 内省路径在 Android 上不存在（我们只用它解析 Clash YAML，
# 走的是 Map/List，不涉及 Bean 绑定），忽略即可
-dontwarn java.beans.**
