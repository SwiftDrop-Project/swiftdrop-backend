package com.swiftdrop.shared.reference;

/** Vehicle types assigned to SwiftDrop couriers. */
public enum VehicleType {
    MOTORBIKE("Motorbike"),
    THREE_WHEELER("Three-Wheeler"),
    VAN("Van"),
    LORRY("Lorry");

    private final String displayName;

    VehicleType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

