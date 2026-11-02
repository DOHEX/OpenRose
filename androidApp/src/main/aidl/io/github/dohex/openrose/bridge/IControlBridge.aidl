package io.github.dohex.openrose.bridge;

import android.os.Bundle;
import io.github.dohex.openrose.bridge.IControlFrames;
import io.github.dohex.openrose.bridge.IControlObserver;

/**
 * The module host's authenticated endpoint, served from com.android.bluetooth. Finding the Binder
 * grants nothing: every call is authorised from the Binder caller's uid, a lease and the owner generation.
 */
interface IControlBridge {
    /** Authorises the caller. The role comes from the verified uid, never from the request. */
    Bundle open(in Bundle hello, IBinder clientToken);
    Bundle currentSession(String leaseId);
    Bundle read(in Bundle request);
    Bundle acknowledge(in Bundle receipt);
    void close(String leaseId);
    Bundle watch(in Bundle request, IControlObserver observer);
    Bundle claimPrompt(in Bundle request);
    Bundle setNoise(in Bundle request);
    Bundle reportPresentation(in Bundle report);
    Bundle reportComponent(in Bundle report);

    // Full client surface, application role only.
    /** Current owner status of the host. */
    Bundle ownerStatus(in Bundle request);
    /** Connect, scan, disconnect, every device setting and preferences, for one owner generation. */
    Bundle execute(in Bundle request);
    /** Handoff: release the module owner for a switch request. */
    Bundle release(in Bundle request);
    /** Handoff: establish the module owner for a switch request. */
    Bundle acquire(in Bundle request);
    /** Streams the owner's published state for one generation. */
    Bundle watchFrames(in Bundle request, IControlFrames frames);
}
