package com.swiftdrop.dispatch;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.swiftdrop.dispatch.AssignmentStatus.CANCELLED;
import static com.swiftdrop.dispatch.AssignmentStatus.COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;

class AssignmentStatusTest {

    @ParameterizedTest
    @EnumSource(AssignmentStatus.class)
    void onlyCompletedAndCancelledAreClosed(AssignmentStatus status) {
        assertThat(status.isClosed()).isEqualTo(status == COMPLETED || status == CANCELLED);
    }
}
