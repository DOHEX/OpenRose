package io.github.dohex.openrose.module.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.module.systemIntegrationResult
import java.io.File
import java.io.FileOutputStream

internal class SystemPromptMedia(private val targetContext: Context, private val handler: Handler) {
    private val ownedAssets = linkedMapOf<Uri, OwnedAsset>()
    private val assetLock = Any()

    fun prepare(request: PromptRequest, token: String): Result<List<PreparedClip>> {
        val clips = mutableListOf<PreparedClip>()
        return systemIntegrationResult {
            val provider = checkNotNull(targetContext.packageManager.resolveContentProvider(FILE_PROVIDER_AUTHORITY, 0)) {
                "target_fileprovider_missing"
            }
            if (provider.packageName != TARGET_PACKAGE) throw SecurityException("target_fileprovider_package_mismatch")
            val moduleResources = runCatching {
                targetContext.createPackageContext(BridgeWire.APP_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            var acquired = false
            try {
                listOf(
                    ClipSpec("left", "earphone_left", MODULE_LEFT_RAW),
                    ClipSpec("right", "earphone_right", MODULE_RIGHT_RAW),
                ).forEach { spec ->
                    val resource = checkNotNull(
                        findRawResource(targetContext, spec.targetRawName)
                            ?: moduleResources?.let { findRawResource(it, spec.moduleRawName) },
                    ) { "${spec.side}_clip_missing" }
                    val name = "openrose_system_${stableDigest("${request.key}:$token:${spec.side}").take(24)}_${spec.side}.mp4"
                    val directory = File(targetContext.filesDir, PROVIDER_FILES_DIRECTORY).also { it.mkdirs() }
                    val file = File(directory, name)
                    copyRawResource(resource.context, resource.resourceId, file)
                    val uri = Uri.Builder()
                        .scheme("content")
                        .authority(FILE_PROVIDER_AUTHORITY)
                        .appendPath("internal_files")
                        .appendPath(name)
                        .build()
                    val clip = PreparedClip(file, uri)
                    clips += clip
                    synchronized(assetLock) { ownedAssets[uri] = OwnedAsset(clip) }
                    probeProviderVideo(uri)
                    targetContext.grantUriPermission(SYSTEM_UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                acquired = true
                clips
            } finally {
                if (!acquired) revoke(clips)
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun findRawResource(context: Context, name: String): RawResource? {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        return id.takeIf { it != 0 }?.let { RawResource(context, it) }
    }

    private fun copyRawResource(context: Context, resourceId: Int, target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            context.resources.openRawResource(resourceId).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (!temporary.isFile || temporary.length() <= 0L) throw IllegalStateException("empty_system_clip")
            if (!temporary.renameTo(target)) throw IllegalStateException("system_clip_copy_commit_failed")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun probeProviderVideo(uri: Uri) {
        val descriptor = checkNotNull(targetContext.contentResolver.openAssetFileDescriptor(uri, "r")) { "target_provider_open_failed" }
        descriptor.use { check(it.length != 0L) { "target_provider_empty_clip" } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(targetContext, uri)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "target_clip_has_no_video_track" }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            check(width > 0 && height > 0) { "target_clip_dimensions_unavailable" }
        } finally {
            retriever.release()
        }
    }

    fun revoke(clips: List<PreparedClip>) {
        val callbacks = synchronized(assetLock) {
            val removedCallbacks = clips.mapNotNull { ownedAssets.remove(it.uri)?.cleanup }.distinct()
            val remainingCallbacks = ownedAssets.values.mapNotNull { it.cleanup }.toSet()
            removedCallbacks.filterNot { it in remainingCallbacks }
        }
        callbacks.forEach(handler::removeCallbacks)
        releaseAssets(clips)
    }

    private fun releaseAssets(clips: List<PreparedClip>) {
        clips.forEach { clip ->
            runCatching { targetContext.revokeUriPermission(clip.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { clip.file.delete() }
        }
    }

    fun revokeAfter(
        clips: List<PreparedClip>,
        delayMillis: Long,
        preserveReadGrace: Boolean = false,
    ) {
        val delay = delayMillis.coerceAtLeast(0L)
        val assets = synchronized(assetLock) { clips.mapNotNull { ownedAssets[it.uri] }.distinct() }
        if (assets.isEmpty()) return

        val deadline = SystemClock.elapsedRealtime() + delay
        val cleanup = object : Runnable {
            override fun run() {
                val releasable = synchronized(assetLock) {
                    assets.filter { asset ->
                        ownedAssets[asset.clip.uri] === asset && asset.cleanup === this
                    }.onEach { asset -> asset.cleanup = null }
                }
                revoke(releasable.map { it.clip })
            }
        }
        synchronized(assetLock) {
            assets.forEach { asset ->
                asset.cleanup?.let(handler::removeCallbacks)
                asset.cleanup = cleanup
                if (preserveReadGrace) {
                    asset.readGraceDeadlineElapsed = maxOf(asset.readGraceDeadlineElapsed, deadline)
                }
            }
        }
        if (!handler.postDelayed(cleanup, delay)) {
            synchronized(assetLock) {
                assets.forEach { asset -> if (asset.cleanup === cleanup) asset.cleanup = null }
            }
            revoke(assets.map { it.clip })
        }
    }

    private data class ClipSpec(val side: String, val targetRawName: String, val moduleRawName: String)
    private data class RawResource(val context: Context, val resourceId: Int)
    private data class OwnedAsset(
        val clip: PreparedClip,
        var cleanup: Runnable? = null,
        var readGraceDeadlineElapsed: Long = 0L,
    )
}

internal data class PreparedClip(val file: File, val uri: Uri)

private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
private const val FILE_PROVIDER_AUTHORITY = "com.xiaomi.bluetooth.fileprovider"
private const val PROVIDER_FILES_DIRECTORY = "my_internal_files"
internal const val URI_RELEASE_GRACE_MILLIS = 750L
private const val MODULE_LEFT_RAW = "openrose_earbud_left"
private const val MODULE_RIGHT_RAW = "openrose_earbud_right"
