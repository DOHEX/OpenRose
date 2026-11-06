package io.github.dohex.openrose

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.*
import io.github.dohex.openrose.bridge.FrameworkLink
import io.github.dohex.openrose.bridge.ModuleSettingsPublisher
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.module.readHostSettings
import io.github.dohex.openrose.ui.*
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidSettingsStoreTest {
    @Test
    fun everyLayerSurvivesReopeningStorageAndRebuildingTheAndroidRuntime() = runTest {
        val file = Files.createTempFile("openrose-settings", ".properties")
        try {
            for (way in ControlWay.entries) {
                val store = AndroidSettingsStore { preferences(file) }
                val chosen = HostSettings(
                    UserSettings(forceBle = true),
                    IntegrationSettings(
                        islandMode = IslandMode.MODULE,
                        focusNotificationEnabled = false,
                        islandTriggers = persistentSetOf(),
                        fusionSettingsDestination = FusionSettingsDestination.OPENROSE,
                        fusionOffAsWind = true,
                        backgroundControlEnabled = true,
                        controlWay = way,
                    ),
                )
                val chosenUi = UiSettings(
                    interfaceStyle = InterfaceStyle.MATERIAL3,
                    appearance = Appearance.DARK,
                    pageTransition = PageTransition.FADE,
                    backgroundBlurEnabled = false,
                    bottomBarStyle = BottomBarStyle.FLOATING,
                )
                // Start from the saved owner selection, then change preferences through the real runtime.
                store.saveHost(HostSettings(integration = IntegrationSettings(controlWay = way)), commit = true)
                val process = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
                val first = runtime(file, process)
                first.updateSettings(chosen.control)
                first.updateIntegrationSettings(chosen.integration)
                store.saveHost(HostSettings(first.state.value.settings, first.integration.value.settings), commit = true)
                store.saveUi(chosenUi)
                process.cancel()

                val rebuilt = runtime(file)
                assertEquals(chosen.control, rebuilt.state.value.settings)
                assertEquals(chosen.integration, rebuilt.integration.value.settings)
                assertEquals(chosenUi, AndroidSettingsStore { preferences(file) }.load().ui)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun persistedAppConfigurationStartsTheHostWithMatchingControlAndIntegrationPreferences() = runTest {
        val file = Files.createTempFile("openrose-host-settings", ".properties")
        val remoteFile = Files.createTempFile("openrose-remote-settings", ".properties")
        try {
            val app = AndroidSettingsStore { preferences(file) }
            val chosen = HostSettings(
                UserSettings(forceBle = true),
                IntegrationSettings(controlWay = ControlWay.MODULE, fusionOffAsWind = true, islandMode = IslandMode.OFF),
            )
            app.saveHost(chosen, commit = true)
            app.saveUi(UiSettings(interfaceStyle = InterfaceStyle.MATERIAL3))
            val publisher = ModuleSettingsPublisher { preferences(remoteFile) }
            assertTrue(publisher.publish(app::loadHost))
            val configured = readHostSettings(preferences(remoteFile))
            val host = ModuleControlHost(
                packageName = "com.android.bluetooth",
                processName = "com.android.bluetooth",
                incarnation = "settings-host",
                authority = CallerAuthority { ClientRole.APP },
                runtimeFactory = { scope, settings, platform, base ->
                    LocalControlRuntime(platform, settings, scope = scope, sessionIdBase = base)
                },
                parentScope = backgroundScope,
                newId = { "lease" },
            )
            host.configure(HostConfiguration(userUnlocked = true, settings = configured))
            runCurrent()
            assertIs<HostStatus.Active>(host.status.value)
            val owner = requireNotNull(host.ownerView.first())
            assertEquals(chosen.control, owner.state.settings)
            assertEquals(chosen.integration, owner.settings)
            host.stop()
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(remoteFile)
        }
    }

    private fun TestScope.runtime(file: Path, scope: CoroutineScope = backgroundScope): SwitchingControlRuntime {
        val saved = AndroidSettingsStore { preferences(file) }.load().host
        return SwitchingControlRuntime(
            initialPlatformState = PlatformState(permission = PermissionState.NOT_REQUIRED),
            initialSettings = saved.control,
            initialIntegrationSettings = saved.integration,
            store = AndroidOwnerStore({ preferences(file) }, FrameworkLink {}),
            ordinaryFactory = { scope, settings, platform, base ->
                LocalControlRuntime(platform, settings, scope = scope, sessionIdBase = base)
            },
            moduleLink = ModuleHostLink { LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "No hardware in this storage test") },
            scope = scope,
            local = LocalOwnerIdentity("io.github.dohex.openrose", "io.github.dohex.openrose"),
            newId = { "runtime" },
        )
    }

    /** File-backed substitute for the Android storage API; application codecs and runtimes are real. */
    private fun preferences(file: Path): SharedPreferences {
        fun load() = Properties().apply { Files.newInputStream(file).use(::load) }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> load().let { values -> values.stringPropertyNames().associateWith(values::getProperty) }
                "edit" -> editor(file, ::load)
                else -> error("Unsupported preference operation: ${method.name}")
            }
        } as SharedPreferences
    }

    private fun editor(file: Path, load: () -> Properties): SharedPreferences.Editor {
        val changes = mutableMapOf<String, String?>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    changes[args[0] as String] = args[1] as String?
                    proxy
                }

                "remove" -> {
                    changes[args[0] as String] = null
                    proxy
                }

                "commit", "apply" -> {
                    val values = load()
                    changes.forEach { (key, value) -> if (value == null) values.remove(key) else values.setProperty(key, value) }
                    Files.newOutputStream(file).use { values.store(it, null) }
                    if (method.name == "commit") true else null
                }

                else -> error("Unsupported editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
