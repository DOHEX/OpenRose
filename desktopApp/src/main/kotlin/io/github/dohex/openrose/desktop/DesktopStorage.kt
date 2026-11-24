package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.core.HostPlatform
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class DesktopPaths(
    val settingsFile: File,
    val circulationDirectory: File,
)

internal fun desktopPaths(
    platform: HostPlatform,
    home: File = File(System.getProperty("user.home")),
    env: (String) -> String? = System::getenv,
): DesktopPaths = when (platform) {
    HostPlatform.WINDOWS -> {
        val configBase = env("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, "AppData/Roaming")
        val dataBase = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, "AppData/Local")
        DesktopPaths(
            settingsFile = File(configBase, "OpenRose/settings.properties"),
            circulationDirectory = File(dataBase, "OpenRose/circulation"),
        )
    }

    else -> {
        val configBase = xdgDirectory(env("XDG_CONFIG_HOME")) ?: File(home, ".config")
        val dataBase = xdgDirectory(env("XDG_DATA_HOME")) ?: File(home, ".local/share")
        DesktopPaths(
            settingsFile = File(configBase, "openrose/settings.properties"),
            circulationDirectory = File(dataBase, "openrose/circulation"),
        )
    }
}

private fun xdgDirectory(value: String?): File? = value
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?.takeIf { it.isAbsolute }

internal class DesktopSettingsFile(private val file: File) {
    private val saved = Properties().apply {
        if (file.isFile) {
            file.inputStream().use(::load)
        }
    }

    @Synchronized
    fun load(): Map<String, String> = saved.stringPropertyNames().associateWith { saved.getProperty(it, "") }

    @Synchronized
    fun save(values: Map<String, String>) {
        val next = Properties().apply {
            putAll(saved)
            values.forEach { (key, value) -> setProperty(key, value) }
        }
        val parent = file.absoluteFile.parentFile
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create settings directory: $parent" }
        val temporary = Files.createTempFile(parent.toPath(), ".settings-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { next.store(it, null) }
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        saved.clear()
        saved.putAll(next)
    }
}
