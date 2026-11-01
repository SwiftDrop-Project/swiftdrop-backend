package com.swiftdrop.shared.meta;

/** The 25 administrative districts of Sri Lanka. */
public enum District {
    AMPARA("Ampara"),
    ANURADHAPURA("Anuradhapura"),
    BADULLA("Badulla"),
    BATTICALOA("Batticaloa"),
    COLOMBO("Colombo"),
    GALLE("Galle"),
    GAMPAHA("Gampaha"),
    HAMBANTOTA("Hambantota"),
    JAFFNA("Jaffna"),
    KALUTARA("Kalutara"),
    KANDY("Kandy"),
    KEGALLE("Kegalle"),
    KILINOCHCHI("Kilinochchi"),
    KURUNEGALA("Kurunegala"),
    MANNAR("Mannar"),
    MATALE("Matale"),
    MATARA("Matara"),
    MONARAGALA("Monaragala"),
    MULLAITIVU("Mullaitivu"),
    NUWARA_ELIYA("Nuwara Eliya"),
    POLONNARUWA("Polonnaruwa"),
    PUTTALAM("Puttalam"),
    RATNAPURA("Ratnapura"),
    TRINCOMALEE("Trincomalee"),
    VAVUNIYA("Vavuniya");

    private final String displayName;

    District(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
