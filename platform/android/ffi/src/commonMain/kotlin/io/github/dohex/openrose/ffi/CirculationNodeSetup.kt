package io.github.dohex.openrose.ffi

import uniffi.openrose_ffi.LocalAudio
import uniffi.openrose_ffi.OwnerActive
import uniffi.openrose_ffi.PeerDiscovery
import uniffi.openrose_ffi.PeerRouteDialer

class CirculationNodeSetup(
    val storageDir: String,
    val name: String,
    val listenPort: UShort?,
    val discovery: PeerDiscovery,
    val audio: LocalAudio,
    val dialer: PeerRouteDialer,
    val ownerActive: OwnerActive,
)
