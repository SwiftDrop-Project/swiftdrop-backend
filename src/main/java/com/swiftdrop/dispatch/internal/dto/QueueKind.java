package com.swiftdrop.dispatch.internal.dto;

/** Which of the dispatcher's two queues to show. */
public enum QueueKind {
    /** Parcels waiting to be collected from their senders in the branch's district (status CREATED). */
    PICKUP,
    /** Parcels at the branch waiting for a transfer or the final delivery (status AT_BRANCH). */
    AT_BRANCH
}
