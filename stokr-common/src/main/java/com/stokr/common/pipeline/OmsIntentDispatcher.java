package com.stokr.common.pipeline;

import com.stokr.common.pipeline.messages.SignalPersistedMessage;

/**
 * Routes persisted signals to OMS (sync inline or async queue).
 */
public interface OmsIntentDispatcher {

    /**
     * @param message signal persisted envelope
     * @param synchronous when true, runs OMS → risk → execution inline (target &lt;3s)
     */
    void dispatch(SignalPersistedMessage message, boolean synchronous);
}
