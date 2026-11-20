package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.ServiceReportTool.Period;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an admin's report request: "today's report", "weekly report", "report for last month", "monthly report for
 * October", "daily summary for 2026-11-10". The period and a day in it; today when no day is named.
 *
 * @param period null when the message asks for a report without saying daily, weekly or monthly
 */
record ReportRequest(Period period, LocalDate day) {

    private static final Pattern REPORT = Pattern.compile(
            "\\b(reports?|summary|summari[sz]e|stats|statistics|overview|performance)\\b"
                    + "|\\bhow many parcels\\b|\\bhow (?:did|was|were|is|are)\\b.*\\b(today|yesterday|week|month)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY = Pattern.compile("\\b(daily|today|today's|yesterday|day)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY = Pattern.compile("\\b(weekly|week|week's)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTHLY = Pattern.compile("\\b(monthly|month|month's)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(" + monthNames() + ")[a-z]*\\.?(?:\\s+(\\d{4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH = Pattern.compile("\\b(" + monthNames() + ")[a-z]*\\.?(?:\\s+(\\d{4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST = Pattern.compile("\\b(last|previous|past)\\s+(week|month)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Asked when the period is missing; the answer to it is read as a report request. */
    static final String ASK_PERIOD = "Which report would you like: daily, weekly or monthly?";

    /** Whether the message asks for a report at all. */
    static boolean isReport(String text) {
        return REPORT.matcher(text).find();
    }

    /** Whether the message names a period ("weekly", "this month", "yesterday"). */
    static boolean namesPeriod(String text) {
        return DAILY.matcher(text).find() || WEEKLY.matcher(text).find() || MONTHLY.matcher(text).find()
                || MONTH.matcher(text).find() || ISO_DATE.matcher(text).find();
    }

    static ReportRequest read(String text, LocalDate today) {
        LocalDate day = today;
        Period period = null;

        Matcher last = LAST.matcher(text);
        Matcher iso = ISO_DATE.matcher(text);
        Matcher dayMonth = DAY_MONTH.matcher(text);
        Matcher month = MONTH.matcher(text);
        if (last.find()) {
            boolean week = last.group(2).equalsIgnoreCase("week");
            day = week ? today.minusWeeks(1) : today.minusMonths(1);
            period = week ? Period.WEEKLY : Period.MONTHLY;
        } else if (text.toLowerCase(Locale.ROOT).contains("yesterday")) {
            day = today.minusDays(1);
            period = Period.DAILY;
        } else if (iso.find()) {
            day = parse(iso.group(1), iso.group(2), iso.group(3)).orElse(today);
        } else if (dayMonth.find()) {
            Month name = month(dayMonth.group(2));
            int year = dayMonth.group(3) == null ? today.getYear() : Integer.parseInt(dayMonth.group(3));
            day = parse(String.valueOf(year), String.valueOf(name.getValue()), dayMonth.group(1)).orElse(today);
            if (dayMonth.group(3) == null && day.isAfter(today)) {
                day = day.minusYears(1);
            }
        } else if (month.find()) {
            Month name = month(month.group(1));
            int year = month.group(2) == null ? today.getYear() : Integer.parseInt(month.group(2));
            day = LocalDate.of(year, name, 1);
            if (month.group(2) == null && day.isAfter(today)) {
                day = day.minusYears(1);
            }
            period = Period.MONTHLY;
        }

        // The words win over what the date suggests ("weekly report for 10 Nov").
        if (Pattern.compile("\\bdaily\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            period = Period.DAILY;
        } else if (Pattern.compile("\\bweekly\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            period = Period.WEEKLY;
        } else if (Pattern.compile("\\bmonthly\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            period = Period.MONTHLY;
        } else if (period == null) {
            if (WEEKLY.matcher(text).find()) {
                period = Period.WEEKLY;
            } else if (MONTHLY.matcher(text).find()) {
                period = Period.MONTHLY;
            } else if (DAILY.matcher(text).find() || iso.reset().find() || dayMonth.reset().find()) {
                period = Period.DAILY;
            }
        }
        return new ReportRequest(period, day);
    }

    private static Optional<LocalDate> parse(String year, String month, String day) {
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Month month(String name) {
        String prefix = name.substring(0, 3).toLowerCase(Locale.ROOT);
        for (Month month : Month.values()) {
            if (month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT).equals(prefix)) {
                return month;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static String monthNames() {
        return "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec";
    }
}
