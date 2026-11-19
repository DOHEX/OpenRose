package io.github.dohex.openrose.bridge;

import android.os.Bundle;

/** Owner state pushed to an authorised application lease; each frame names the generation that produced it. */
oneway interface IControlFrames {
    void onFrame(in Bundle frame);
    /** The generation ended or the lease was revoked; nothing received before this is current any more. */
    void onEnded(in Bundle end);
}
