package io.github.dohex.openrose

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.dohex.openrose.androidintegration.CompanionAssociation
import io.github.dohex.openrose.androidintegration.PresenceObservation
import io.github.dohex.openrose.androidintegration.WakeBand
import io.github.dohex.openrose.androidintegration.WakeFacts
import java.util.regex.Pattern

/**
 * The public system mechanism that revives OpenRose when the earphones appear and the process is
 * gone. It differs by Android version and nothing here assumes one mechanism covers all of them:
 * before Android 12 there is no presence API (the manifest receiver for audio-connection broadcasts
 * is the only path and no association is needed); from Android 12 an association plus presence
 * observation wakes a [OpenRoseCompanionService], and the association also carries the foreground
 * service start exemption.
 */
internal class CompanionWake(private val context: Context) {
    private data class Association(val id: Int?, val address: String)

    @Volatile
    private var presence = PresenceObservation.NOT_OBSERVING

    val band: WakeBand = when {
        Build.VERSION.SDK_INT >= 34 -> WakeBand.ANDROID_14_PLUS
        Build.VERSION.SDK_INT >= 31 -> WakeBand.ANDROID_12_13
        else -> WakeBand.ANDROID_8_11
    }

    private val manager: CompanionDeviceManager?
        get() = context.getSystemService(CompanionDeviceManager::class.java)

    private val supported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) && manager != null

    /** Facts re-read from the system on every call, so a cancelled association stops reading as ready. */
    fun facts(): WakeFacts {
        if (band == WakeBand.ANDROID_8_11) return WakeFacts(band)
        if (!supported) return WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.REFUSED)
        val associated = associations().isNotEmpty()
        return if (associated) {
            WakeFacts(band, CompanionAssociation.ACTIVE, presence)
        } else {
            WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING)
        }
    }

    /**
     * Aligns the system's presence observation with the saved intent: observing while background
     * control is enabled, withdrawn when the user disables it. Repeating it is harmless.
     */
    fun syncObservation(enabled: Boolean) {
        // The same boundary as WakeBand.ANDROID_8_11, written as an SDK check so lint sees the API 31 guard.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !supported) return
        val associated = associations()
        if (associated.isEmpty()) {
            presence = PresenceObservation.NOT_OBSERVING
            return
        }
        if (!enabled) {
            associated.forEach { runCatching { stopObserving(it) } }
            presence = PresenceObservation.NOT_OBSERVING
            return
        }
        presence = try {
            associated.forEach(::startObserving)
            PresenceObservation.OBSERVING
        } catch (refused: RuntimeException) {
            // Missing association or permission: reported as it is, never retried in a loop.
            PresenceObservation.REFUSED
        }
    }

    /** Asks the system to show its device chooser; [onChooser] launches it from an Activity. */
    fun requestAssociation(onChooser: (IntentSender) -> Unit, onFailure: () -> Unit) {
        val manager = manager?.takeIf { supported } ?: return onFailure()
        val request = associationRequest()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)
                    override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit
                    override fun onFailure(error: CharSequence?) = onFailure()
                })
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, object : CompanionDeviceManager.Callback() {
                    @Deprecated("Replaced by onAssociationPending on newer Android versions")
                    override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
                    override fun onFailure(error: CharSequence?) = onFailure()
                }, null)
            }
        } catch (refused: RuntimeException) {
            onFailure()
        }
    }

    private fun associationRequest(): AssociationRequest {
        // Mirrors ModelProfile.match: names starting with "rose" or containing earfeel/earfree.
        val name = Pattern.compile("(?is)rose.*|.*ear(?:feel|free).*")
        return AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setNamePattern(name).build())
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setNamePattern(name).build())
            .setSingleDevice(false)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun associations(): List<Association> {
        val manager = manager ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations.mapNotNull { info ->
                    info.deviceMacAddress?.toString()?.uppercase()?.let { Association(info.id, it) }
                }
            } else {
                manager.associations.map { Association(null, it.uppercase()) }
            }
        } catch (unavailable: RuntimeException) {
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("DEPRECATION")
    private fun startObserving(association: Association) {
        val manager = requireNotNull(manager)
        if (Build.VERSION.SDK_INT >= 36 && association.id != null) {
            manager.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
            )
        } else {
            manager.startObservingDevicePresence(association.address)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("DEPRECATION")
    private fun stopObserving(association: Association) {
        val manager = requireNotNull(manager)
        if (Build.VERSION.SDK_INT >= 36 && association.id != null) {
            manager.stopObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
            )
        } else {
            manager.stopObservingDevicePresence(association.address)
        }
    }
}
