package com.hmdm.remote;

/** Pure server-side lifecycle vocabulary; persistence and endpoints arrive in the next slice. */
public enum RemoteViewSessionState {
    REQUESTED, AWAITING_DEVICE, AWAITING_CONSENT, SIGNALING, CONNECTED,
    DENIED, EXPIRED, STOPPED, DISCONNECTED, FAILED;

    public boolean terminal() {
        return this == DENIED || this == EXPIRED || this == STOPPED || this == DISCONNECTED || this == FAILED;
    }

    public boolean permits(RemoteViewSessionState next) {
        if (terminal()) return false;
        if (next == STOPPED || next == EXPIRED || next == FAILED || next == DISCONNECTED) return true;
        switch (this) {
            case REQUESTED: return next == AWAITING_DEVICE;
            case AWAITING_DEVICE: return next == AWAITING_CONSENT;
            case AWAITING_CONSENT: return next == SIGNALING || next == DENIED;
            case SIGNALING: return next == CONNECTED;
            default: return false;
        }
    }
}
