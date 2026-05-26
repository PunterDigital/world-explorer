# Keep osmdroid classes — accessed via reflection in some configurations
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
