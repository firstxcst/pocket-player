# Media3 自带 consumer rules，这里只保留 Kotlin/协程反射所需元数据
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.coroutines.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
# NextLib FFmpeg 解码扩展（含反射加载与 JNI），R8 不要裁掉
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.media3ext.**
