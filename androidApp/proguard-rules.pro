# OpenRose is GPL software: shrink and optimize, but keep names so stack traces and reflective
# lookups stay readable and stable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# LSPosed instantiates the module entry named in META-INF/xposed/java_init.list by reflection.
-keep class io.github.dohex.openrose.module.OpenRoseBluetoothModule {
    public <init>();
}
