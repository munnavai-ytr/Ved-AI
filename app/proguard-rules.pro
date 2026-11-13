# Keep line numbers for readable stack traces
-keepattributes SourceFile,LineNumberTable

# Keep Moshi generated adapters and Retrofit models
-keep class com.example.data.remote.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Keep kotlinx.serialization Navigation routes
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.example.ui.navigation.** {
    *;
}
