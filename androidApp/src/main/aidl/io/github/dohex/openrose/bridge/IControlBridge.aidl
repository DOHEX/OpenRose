package io.github.dohex.openrose.bridge;

import android.os.Bundle;
import io.github.dohex.openrose.bridge.IControlObserver;
import io.github.dohex.openrose.bridge.IStrongToastGuard;

/** Reference-checked access to the application's existing authority. */
interface IControlBridge {
    Bundle open(in Bundle proof, IBinder clientToken);
    Bundle currentSession(String leaseId);
    Bundle read(in Bundle request);
    Bundle acknowledge(in Bundle receipt);
    void close(String leaseId);
    Bundle watch(in Bundle request, IControlObserver observer);
    Bundle claimPrompt(in Bundle request);
    Bundle setNoise(in Bundle request);
    Bundle reportPresentation(in Bundle report);
    Bundle reportComponent(in Bundle report);
    Bundle registerToastGuard(in Bundle request, IStrongToastGuard guard);
    Bundle postOfficial(in Bundle request);
    Bundle withdrawOfficial(in Bundle request);
}
