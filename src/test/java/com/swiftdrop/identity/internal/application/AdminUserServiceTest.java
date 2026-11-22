package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.MerchantProfiles;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.dto.AdminUserDetailResponse;
import com.swiftdrop.identity.internal.dto.UserStatsResponse;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.swiftdrop.identity.internal.dto.UpdateUserStatusRequest.Change.ACTIVE;
import static com.swiftdrop.identity.internal.dto.UpdateUserStatusRequest.Change.BLOCKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The safety rules of admin user management, without a database. */
class AdminUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T05:00:00Z");
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private final UserRepository users = mock(UserRepository.class);
    private final MerchantProfiles merchantProfiles = mock(MerchantProfiles.class);
    private final SessionStateCache sessions = mock(SessionStateCache.class);
    private final VerificationCodeService codes = mock(VerificationCodeService.class);
    private final AdminUserService service = new AdminUserService(users, merchantProfiles, sessions, codes,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anAdminCannotBlockOrUnblockTheirOwnAccount() {
        assertThatThrownBy(() -> service.changeStatus(ADMIN_ID, BLOCKED, ADMIN_ID))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CANNOT_CHANGE_OWN_ACCOUNT));
        assertThatThrownBy(() -> service.changeStatus(ADMIN_ID, ACTIVE, ADMIN_ID))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(users);
    }

    @Test
    void blockingAnActiveAccountDisablesItAndEndsItsSessions() {
        UUID id = UUID.randomUUID();
        User customer = user(Role.CUSTOMER, UserStatus.ACTIVE);
        int versionBefore = customer.getTokenVersion();
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(customer));

        AdminUserDetailResponse result = service.changeStatus(id, BLOCKED, ADMIN_ID);

        assertThat(result.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(customer.getTokenVersion()).isGreaterThan(versionBefore);
        verify(sessions).evict(id);
        verify(codes).invalidateAll(id, VerificationPurpose.PASSWORD_RESET);
        verify(users).saveAndFlush(customer);
    }

    @Test
    void onlyActiveAccountsCanBeBlocked() {
        UUID id = UUID.randomUUID();
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user(Role.MERCHANT, UserStatus.PENDING_APPROVAL)));

        assertThatThrownBy(() -> service.changeStatus(id, BLOCKED, ADMIN_ID))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void onlyBlockedAccountsCanBeUnblocked() {
        UUID id = UUID.randomUUID();
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user(Role.CUSTOMER, UserStatus.PENDING_EMAIL_VERIFICATION)));

        assertThatThrownBy(() -> service.changeStatus(id, ACTIVE, ADMIN_ID))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_BLOCKED));
    }

    @Test
    void unblockingRestoresActive() {
        UUID id = UUID.randomUUID();
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user(Role.COURIER, UserStatus.DISABLED)));

        assertThat(service.changeStatus(id, ACTIVE, ADMIN_ID).status()).isEqualTo(UserStatus.ACTIVE);
        verify(sessions).evict(id);
    }

    @Test
    void askingForTheCurrentStatusChangesNothing() {
        UUID blockedId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        when(users.findByIdForUpdate(blockedId)).thenReturn(Optional.of(user(Role.CUSTOMER, UserStatus.DISABLED)));
        when(users.findByIdForUpdate(activeId)).thenReturn(Optional.of(user(Role.CUSTOMER, UserStatus.ACTIVE)));

        assertThat(service.changeStatus(blockedId, BLOCKED, ADMIN_ID).status()).isEqualTo(UserStatus.DISABLED);
        assertThat(service.changeStatus(activeId, ACTIVE, ADMIN_ID).status()).isEqualTo(UserStatus.ACTIVE);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(sessions);
    }

    @Test
    void removedAccountsCannotBeChanged() {
        UUID id = UUID.randomUUID();
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user(Role.COURIER, UserStatus.REMOVED)));

        assertThatThrownBy(() -> service.changeStatus(id, ACTIVE, ADMIN_ID))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_REMOVED));
    }

    @Test
    void aStartDateAfterTheEndDateIsRejected() {
        var search = new AdminUserService.UserSearch(null, null, null, LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 1), null);

        assertThatThrownBy(() -> service.list(search, 0, 20))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("registeredFrom"));
        verifyNoInteractions(users);
    }

    @Test
    void statsCountNewAccountsFromTheLast30Days() {
        when(users.countByStatusNot(UserStatus.REMOVED)).thenReturn(1250L);
        when(users.countByStatus(UserStatus.ACTIVE)).thenReturn(1100L);
        when(users.countByStatus(UserStatus.DISABLED)).thenReturn(100L);
        when(users.countByStatusIn(Set.of(UserStatus.PENDING_EMAIL_VERIFICATION, UserStatus.PENDING_APPROVAL,
                UserStatus.PROFILE_INCOMPLETE))).thenReturn(50L);
        when(users.countByStatusNotAndCreatedAtGreaterThanEqual(UserStatus.REMOVED,
                Instant.parse("2026-10-14T05:00:00Z"))).thenReturn(40L);

        assertThat(service.stats()).isEqualTo(new UserStatsResponse(1250, 1100, 100, 50, 40));
    }

    private static User user(Role role, UserStatus status) {
        return new User("Test User", "user@test.lk", "+94711234567", "hash", role, status);
    }
}
