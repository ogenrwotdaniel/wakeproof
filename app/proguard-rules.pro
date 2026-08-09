# R8 rules for the release build.
#
# Most of what this app uses ships its own consumer rules (Room, Hilt, Firebase,
# CameraX, ML Kit, OkHttp, WorkManager), so this file only covers the cases those
# cannot know about.

# --- kotlinx.serialization -----------------------------------------------------
# The compiler plugin generates a `Companion.serializer()` for each @Serializable
# class and looks it up reflectively. R8 cannot see that reference, so without
# this the AI request/response DTOs fail to serialize at runtime — and only at
# runtime, on the one code path a release smoke test is least likely to cover.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ogenrwotdaniel.wakeproof.**$$serializer { *; }
-keepclasseswithmembers class com.ogenrwotdaniel.wakeproof.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.ogenrwotdaniel.wakeproof.**
-keep, allowobfuscation, allowshrinking class <1>

# --- Room entities -------------------------------------------------------------
# Room generates code rather than reflecting, so entities do not strictly need
# keeping. They are kept anyway because their field names are the Firestore
# document keys: obfuscating them would silently change the wire format and
# desynchronise devices running different builds.
-keep class com.ogenrwotdaniel.wakeproof.data.db.** { *; }

# --- Enums ---------------------------------------------------------------------
# AlarmType, GoalKind, CompletionStatus and friends travel as their `name` in both
# Firestore documents and the Wearable Data Layer payload, so the names have to
# survive obfuscation on both ends of a sync.
-keepclassmembers enum com.ogenrwotdaniel.wakeproof.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** enumValues();
    public static ** enumValueOf(java.lang.String);
}

# --- Crash reports -------------------------------------------------------------
# Without this a Play Console stack trace points at obfuscated line numbers, which
# makes a user-reported alarm failure close to untraceable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
