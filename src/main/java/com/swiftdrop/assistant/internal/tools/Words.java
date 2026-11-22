package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.shared.reference.District;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How the assistant says things: money, dates, statuses, tracking numbers and district names. */
public final class Words {

    public static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    /** Where to send anything SwiftDrop's data can't answer (the help pages show the same address). */
    public static final String CONTACT_TEAM = "please contact our team at support@swiftdrop.lk";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(SRI_LANKA);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    /** District names, longest first so "Nuwara Eliya" wins over shorter matches. */
    private static final List<District> BY_NAME_LENGTH = Arrays.stream(District.values())
            .sorted(Comparator.comparingInt((District d) -> d.displayName().length()).reversed())
            .toList();

    private Words() {
    }

    /** 1234.5 → "Rs. 1,234.50" */
    public static String rupees(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "Rs. " + format.format(amount);
    }

    /** BIKE → "bike", TUK_TUK → "tuk-tuk", LORRY → "lorry" */
    public static String vehicle(Vehicle vehicle) {
        return switch (vehicle) {
            case BIKE -> "bike";
            case TUK_TUK -> "tuk-tuk";
            case LORRY -> "lorry";
        };
    }

    /** "SD2611004817" → "SD 2611 0048 17" */
    public static String trackingNumber(String number) {
        if (number == null || number.length() != 12) {
            return number;
        }
        return number.substring(0, 2) + " " + number.substring(2, 6) + " " + number.substring(6, 10) + " "
                + number.substring(10);
    }

    /** Sri Lankan time, e.g. "14 Nov 2026, 10:56 PM". */
    public static String when(Instant instant) {
        return instant == null ? null : WHEN.format(instant);
    }

    /** "16 Nov 2026" */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    /** "Mon 16 Nov" */
    public static String shortDay(LocalDate date) {
        return SHORT_DAY.format(date);
    }

    /** A parcel status as a person would say it. */
    public static String status(ParcelStatus status, District branch, FailureReason reason) {
        return switch (status) {
            case CREATED -> "booked and waiting for a courier to collect it";
            case PICKED_UP -> "collected by a courier, on its way to the branch";
            case AT_BRANCH -> branch == null ? "at a SwiftDrop branch" : "at the " + branch.displayName() + " branch";
            case IN_TRANSIT -> "on its way between branches";
            case OUT_FOR_DELIVERY -> "out for delivery";
            case DELIVERED -> "delivered";
            case FAILED -> "not delivered" + (reason == null ? "" : " (" + failureReason(reason) + ")");
        };
    }

    /** A short label for a status in a count or a table: "Waiting for pickup", "At a branch". */
    public static String statusLabel(ParcelStatus status) {
        return switch (status) {
            case CREATED -> "Waiting for pickup";
            case PICKED_UP -> "Picked up";
            case AT_BRANCH -> "At a branch";
            case IN_TRANSIT -> "In transit";
            case OUT_FOR_DELIVERY -> "Out for delivery";
            case DELIVERED -> "Delivered";
            case FAILED -> "Failed delivery";
        };
    }

    public static String failureReason(FailureReason reason) {
        return switch (reason) {
            case RECIPIENT_UNAVAILABLE -> "the recipient wasn't available";
            case WRONG_ADDRESS -> "the address was wrong";
            case RECIPIENT_REFUSED -> "the recipient refused it";
            case ADDRESS_UNREACHABLE -> "the address couldn't be reached";
            case PARCEL_DAMAGED -> "the parcel was damaged";
            default -> reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    /** "Kandy", "kandy", "KANDY" or "Nuwara Eliya" / "NUWARA_ELIYA" → the district. */
    public static Optional<District> district(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String key = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return Arrays.stream(District.values())
                .filter(d -> d.name().toLowerCase(Locale.ROOT).replace("_", "").equals(key)
                        || d.displayName().toLowerCase(Locale.ROOT).replace(" ", "").equals(key))
                .findFirst();
    }

    /** A district mentioned in free text, with where it starts. */
    public record Mention(District district, int start, int end) {
    }

    /** Every district named in {@code text}, in the order they appear (whole words, any case). */
    public static List<Mention> districtsIn(String text) {
        List<Mention> found = new ArrayList<>();
        boolean[] taken = new boolean[text.length()];
        for (District district : BY_NAME_LENGTH) {
            Matcher matcher = Pattern.compile("\\b" + Pattern.quote(district.displayName()) + "\\b",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            while (matcher.find()) {
                if (!taken[matcher.start()]) {
                    found.add(new Mention(district, matcher.start(), matcher.end()));
                    Arrays.fill(taken, matcher.start(), matcher.end(), true);
                }
            }
        }
        found.sort(Comparator.comparingInt(Mention::start));
        return found;
    }

    /** The district codes, for tool schemas. */
    public static List<String> districtCodes() {
        return Arrays.stream(District.values()).map(District::name).toList();
    }
}
