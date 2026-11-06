package io.github.dohex.openrose.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.libxposed.service.XposedProvider

/** Only a root/system framework dispatcher may supply the framework service Binder. */
class GuardedXposedProvider : ContentProvider() {
    private val delegate = XposedProvider()

    override fun attachInfo(context: Context, info: ProviderInfo) {
        // Retain the library's Direct Boot/RemotePreferences initialization, behind our transport.
        delegate.attachInfo(context, info)
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = Binder.getCallingUid()
        if (caller != 0 && caller != Process.SYSTEM_UID) {
            throw SecurityException("仅接受 root / system 框架分发的服务")
        }
        return delegate.call(method, arg, extras)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
