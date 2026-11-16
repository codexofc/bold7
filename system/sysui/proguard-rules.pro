# Vector instantiates the entry class named in META-INF/xposed/java_init.list by reflection.
-keep class dev.bold7.sysui.SysuiModule { <init>(); }
# The framework stubs on the compile classpath (hidden task stack listener) name synthetic constructor classes they do not hold.
-dontwarn android.**
