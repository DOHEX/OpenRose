package io.github.dohex.openrose.bridge;

import android.os.Bundle;

/** A wake-up only; state still comes from an authenticated, reference-checked read. */
oneway interface IControlObserver {
    void onChanged(in Bundle identity);
}
