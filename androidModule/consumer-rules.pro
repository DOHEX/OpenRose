# LSPosed instantiates the module entry named in META-INF/xposed/java_init.list by reflection.
-keep class io.github.dohex.openrose.module.OpenRoseBluetoothModule {
    public <init>();
}
