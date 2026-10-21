package io.github.dohex.openrose.bridge;

import android.os.Bundle;

/** System-server ownership mediator. Only the authenticated application host may invoke it. */
interface IStrongToastGuard {
    Bundle post(in Bundle request);
    Bundle withdraw(in Bundle request);
}
