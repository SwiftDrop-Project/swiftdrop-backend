package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.ParcelStatus;

/** One row of {@link ParcelRepository#countPerStatus()}: a status and how many parcels are in it. */
public interface StatusTotal {

    ParcelStatus getStatus();

    long getTotal();
}
