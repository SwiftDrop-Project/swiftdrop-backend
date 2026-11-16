package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The checks behind the delivery-list filters, without a database (the queries are covered by the API tests). */
class DeliveryCriteriaTest {

    private static DeliveryFilters search(String term) {
        return new DeliveryFilters(null, null, term, null, null);
    }

    private static DeliveryFilters dates(String from, String to) {
        return new DeliveryFilters(null, null, null, from, to);
    }

    @Test
    void noFiltersAtAllIsFine() {
        assertThatCode(() -> DeliveryCriteria.parse(DeliveryFilters.NONE)).doesNotThrowAnyException();
        assertThatCode(() -> DeliveryCriteria.parse(new DeliveryFilters(null, " ", "  ", "", " ")))
                .doesNotThrowAnyException();
    }

    @Test
    void aSearchCanBeUpTo64Characters() {
        assertThatCode(() -> DeliveryCriteria.parse(search("x".repeat(64)))).doesNotThrowAnyException();
        // surrounding spaces don't count
        assertThatCode(() -> DeliveryCriteria.parse(search("  " + "x".repeat(64) + "  "))).doesNotThrowAnyException();

        assertThatThrownBy(() -> DeliveryCriteria.parse(search("x".repeat(65))))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.fieldErrors()).containsEntry("search", DeliveryCriteria.SEARCH_TOO_LONG));
    }

    @Test
    void datesMustBeRealDaysWrittenLikeTheExample() {
        assertThatCode(() -> DeliveryCriteria.parse(dates("2026-11-01", "2026-11-15"))).doesNotThrowAnyException();
        assertThatCode(() -> DeliveryCriteria.parse(dates("2026-11-15", "2026-11-15"))).doesNotThrowAnyException();

        for (String bad : new String[] {"2026-13-01", "15/11/2026", "2026-11-31", "yesterday"}) {
            assertThatThrownBy(() -> DeliveryCriteria.parse(dates(bad, null)))
                    .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                            assertThat(ex.fieldErrors()).containsEntry("from", DeliveryCriteria.BAD_DATE));
            assertThatThrownBy(() -> DeliveryCriteria.parse(dates(null, bad)))
                    .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                            assertThat(ex.fieldErrors()).containsEntry("to", DeliveryCriteria.BAD_DATE));
        }
    }

    @Test
    void theEndCantBeBeforeTheStart() {
        assertThatThrownBy(() -> DeliveryCriteria.parse(dates("2026-11-15", "2026-11-14")))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.fieldErrors()).containsEntry("to", DeliveryCriteria.TO_BEFORE_FROM));
    }

    @Test
    void anUnknownEventTypeIsRefusedWithTheCreateWording() {
        assertThatThrownBy(() -> DeliveryCriteria.parse(new DeliveryFilters(null, "parcel.lost", null, null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.fieldErrors()).containsEntry("eventType", "'parcel.lost' isn't a webhook event"));
        assertThat(DeliveryCriteria.parseEventType("webhook.ping")).isNotNull();
    }

    @Test
    void likeWildcardsInTheTermAreLiteral() {
        assertThat(DeliveryCriteria.escapeLike("50%_off\\x")).isEqualTo("50\\%\\_off\\\\x");
        assertThat(DeliveryCriteria.escapeLike("tech-ord-1001")).isEqualTo("tech-ord-1001");
    }
}
