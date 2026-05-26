# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/macbookpro2015/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any custom rules here that might be needed for your libraries.

# Keep ML Kit and OpenCV classes if they are being stripped
-keep class com.google.mlkit.** { *; }
-keep class org.opencv.** { *; }
-keep class com.quickbirdstudios.opencv.** { *; }

# Keep Room database entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class * { @androidx.room.Dao *; }

# Keep Retrofit and Gson models
-keep class com.squareup.retrofit2.** { *; }
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep your own data models if they are used for JSON serialization
-keepclassmembers class com.totalbattle.chestscanner.data.model.** { *; }
