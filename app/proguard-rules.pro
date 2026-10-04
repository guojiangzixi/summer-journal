# ══════════════════════════════════════════════════════════
# 夏日手札 · Release 混淆规则
# ══════════════════════════════════════════════════════════

# ── kotlinx.serialization ──
# 序列化靠 @Serializable 生成的 companion，混淆后必须保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.summer.journal.**$$serializer { *; }
-keepclassmembers class com.summer.journal.** {
    *** Companion;
}
-keepclasseswithmembers class com.summer.journal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Retrofit / OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── Jsoup（教务课表解析）──
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ──
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# ── 领域模型：反射与序列化都要用，别改名字 ──
# （方便 Crash 日志里直接看懂类名，代价很小）
-keep class com.summer.journal.domain.model.** { *; }
-keep class com.summer.journal.data.remote.weather.OpenMeteo* { *; }

# ── 提醒 Receiver：被 Manifest 引用，必须保留 ──
-keep class com.summer.journal.reminder.** { *; }

# ── Compose 一般不需要额外规则，但保留行号便于定位崩溃 ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
