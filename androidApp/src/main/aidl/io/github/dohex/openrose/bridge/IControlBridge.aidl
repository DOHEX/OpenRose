package io.github.dohex.openrose.bridge;

import android.os.Bundle;

/** Read-only access to the application's existing authority. */
interface IControlBridge {
    Bundle open(in Bundle proof, IBinder clientToken);
    Bundle currentSession(String leaseId);
    Bundle read(in Bundle request);
    Bundle acknowledge(in Bundle receipt);
    void close(String leaseId);
}
