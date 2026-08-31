# The entry point is named in plugin manifest metadata and loaded reflectively.
-keep public class * implements dev.enginehost.api.EnginePlugin { public <init>(); }
-keep interface dev.enginehost.api.** { *; }
-keep class dev.enginehost.api.** { *; }
