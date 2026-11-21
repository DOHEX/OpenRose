package io.github.dohex.openrose.transport.bluez

import java.io.File

fun linuxBluetoothSettingsCommand(): List<String>? {
    val directories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
    return listOf(
        listOf("gnome-control-center", "bluetooth"),
        listOf("systemsettings", "kcm_bluetooth"),
        listOf("blueman-manager"),
    ).firstNotNullOfOrNull { candidate ->
        directories.firstNotNullOfOrNull { directory ->
            val executable = File(directory.ifEmpty { "." }, candidate.first())
            if (executable.isFile && executable.canExecute()) listOf(executable.path) + candidate.drop(1) else null
        }
    }
}
