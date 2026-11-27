package io.github.dohex.openrose.module

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class OpenRoseBluetoothModuleReloadRefusalTest {
    @Test
    fun aReloadRequestIsRefusedAndLeavesTheRunningHostUntouched() {
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("reload_refusal_test", Context.MODE_PRIVATE).apply {
            edit().clear().commit()
        }
        val xposed = FakeXposedInterface(app.applicationInfo, preferences)
        val module = OpenRoseBluetoothModule()
        module.attachFramework(xposed) {}

        module.onModuleLoaded(object : XposedModuleInterface.ModuleLoadedParam {
            override fun isSystemServer(): Boolean = false
            override fun getProcessName(): String = BridgeWire.TARGET_PROCESS
        })

        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = if (name == BridgeWire.HOST_SERVICE_CLASS) {
                DummyBluetoothService::class.java
            } else {
                super.loadClass(name, resolve)
            }
        }
        module.onPackageReady(object : XposedModuleInterface.PackageReadyParam {
            override fun getPackageName(): String = BridgeWire.TARGET_PACKAGE
            override fun getApplicationInfo(): ApplicationInfo = app.applicationInfo
            override fun isFirstPackage(): Boolean = true
            override fun getDefaultClassLoader(): ClassLoader = loader
            override fun getClassLoader(): ClassLoader = loader
            override fun getAppComponentFactory() = error("unused")
        })

        val bluetoothContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = BridgeWire.TARGET_PACKAGE
            override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean = false
        }

        val entryHook = xposed.hooksById.getValue("openrose.entry.BLUETOOTH")
        entryHook.hooker.intercept(FakeChain(entryHook.executable, null, listOf(bluetoothContext)))

        val discoveryHook = xposed.hooksById.getValue("openrose.host.discovery")
        val binder = discoveryHook.hooker.intercept(
            FakeChain(discoveryHook.executable, DummyBluetoothService(), listOf(Intent(BridgeWire.ACTION_CONTROL_HOST))),
        ) as IBinder
        val bridge = IControlBridge.Stub.asInterface(binder)
        val hello = BridgeBundles.hello(BridgeWire.TARGET_PROCESS, BridgeTarget.BLUETOOTH)
        val beforeOpen = bridge.open(hello, Binder())
        assertEquals(BridgeWire.STATUS_OK, beforeOpen.getString(BridgeWire.STATUS))
        val initialPayload = beforeOpen.getString(BridgeWire.PAYLOAD)
        assertNotNull(initialPayload)

        var savedState: Any? = null
        var savedStateSet = false
        val refused = module.onHotReloading(object : XposedModuleInterface.HotReloadingParam {
            override fun getExtras(): Bundle? = null
            override fun setSavedInstanceState(outState: Any?) {
                savedStateSet = true
                savedState = outState
            }
        })

        assertFalse(refused)
        assertFalse(savedStateSet)
        assertNull(savedState)
        assertTrue(xposed.logs.any { it.first == Log.WARN && it.second.contains("hot_reload_refused") })
        assertTrue(xposed.unhookedIds.isEmpty())

        val afterOpen = bridge.open(hello, Binder())
        assertEquals(BridgeWire.STATUS_OK, afterOpen.getString(BridgeWire.STATUS))
        assertEquals(initialPayload.substringAfter("\"incarnation\":\"").substringBefore("\""), afterOpen.getString(BridgeWire.PAYLOAD)?.substringAfter("\"incarnation\":\"")?.substringBefore("\""))
    }

    private class DummyBluetoothService : Service() {
        override fun onBind(intent: Intent): IBinder? = null
    }

    private class RecordedHook(
        val id: String?,
        val executable: Executable,
        val hooker: XposedInterface.Hooker,
    )

    private class FakeChain(
        private val executable: Executable,
        private val thisObject: Any?,
        private val args: List<Any?>,
    ) : XposedInterface.Chain {
        override fun getExecutable(): Executable = executable
        override fun getThisObject(): Any? = thisObject
        override fun getArgs(): List<Any?> = args.filterNotNull()
        override fun getArg(index: Int): Any? = args[index]
        override fun proceed(): Any? = null
        override fun proceed(args: Array<out Any?>): Any? = null
        override fun proceedWith(thisObject: Any): Any? = null
        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? = null
    }

    private class FakeXposedInterface(
        private val appInfo: ApplicationInfo,
        private val prefs: SharedPreferences,
    ) : XposedInterface {
        val logs = mutableListOf<Pair<Int, String>>()
        val hooksById = linkedMapOf<String, RecordedHook>()
        val unhookedIds = mutableListOf<String?>()

        override fun getFrameworkName(): String = "LSPosed"
        override fun getFrameworkVersion(): String = "2.2.0"
        override fun getFrameworkVersionCode(): Long = 1L
        override fun getFrameworkProperties(): Long = XposedInterface.PROP_CAP_SYSTEM or XposedInterface.PROP_CAP_REMOTE

        override fun hook(origin: Executable): XposedInterface.HookBuilder = object : XposedInterface.HookBuilder {
            private var id: String? = null
            override fun setPriority(priority: Int) = this
            override fun setExceptionMode(mode: XposedInterface.ExceptionMode) = this
            override fun setId(id: String?) = apply { this.id = id }
            override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                val hook = RecordedHook(id, origin, hooker)
                id?.let { hooksById[it] = hook }
                return object : XposedInterface.HookHandle {
                    override fun getExecutable(): Executable = origin
                    override fun unhook() {
                        unhookedIds += id
                    }
                    override fun getId(): String? = id
                    override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = this
                }
            }
        }

        override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder = error("unused")
        override fun deoptimize(executable: Executable): Boolean = false
        override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> = error("unused")
        override fun <T> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> = error("unused")
        override fun log(priority: Int, tag: String?, msg: String) {
            logs += priority to msg
        }
        override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
            logs += priority to msg
        }
        override fun getModuleApplicationInfo(): ApplicationInfo = appInfo
        override fun getRemotePreferences(group: String): SharedPreferences = prefs
        override fun listRemoteFiles(): Array<String> = emptyArray()
        override fun openRemoteFile(name: String): ParcelFileDescriptor = throw FileNotFoundException(name)
    }
}
