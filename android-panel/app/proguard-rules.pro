# WorkManager brings Room 2.6.1, whose consumer rule keeps RoomDatabase subclasses but not their no-arg
# constructor. R8 full mode then strips WorkDatabase_Impl.<init>() and the app crashes at startup
# (NoSuchMethodException from WorkManagerInitializer). Room 2.7+ ships this rule itself.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
