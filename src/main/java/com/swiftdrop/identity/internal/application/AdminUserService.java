package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.MerchantProfiles;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.dto.AdminUserDetailResponse;
import com.swiftdrop.identity.internal.dto.AdminUserResponse;
import com.swiftdrop.identity.internal.dto.UpdateUserRequest;
import com.swiftdrop.identity.internal.dto.UpdateUserStatusRequest;
import com.swiftdrop.identity.internal.dto.UserSort;
import com.swiftdrop.identity.internal.dto.UserStatsResponse;
import com.swiftdrop.identity.internal.dto.UserStatusFilter;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin user management for every account (customers, merchants, couriers, dispatchers and admins): list, search,
 * view, edit contact details, block and unblock, and the summary figures.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only ACTIVE accounts can be blocked, and unblocking always restores ACTIVE. The database does not remember
 *       the status from before a block, so this keeps unblocking from skipping email verification or merchant
 *       approval.</li>
 *   <li>An admin can never block their own account (so they cannot lock themselves out).</li>
 *   <li>Removed accounts are terminal: they can be viewed but not edited, blocked or unblocked.</li>
 *   <li>Every change locks the user row ({@code PESSIMISTIC_WRITE}) and ends the user's sessions where needed.</li>
 *   <li>Who did what and when is logged with ids only (never names, emails or phone numbers).</li>
 * </ul>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    static final int MAX_PAGE_SIZE = 100;
    static final Duration NEW_USER_PERIOD = Duration.ofDays(30);
    /** Registration-date filters are Sri Lanka days. */
    static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    private static final EnumSet<UserStatus> PENDING = EnumSet.copyOf(UserStatusFilter.PENDING.statuses());

    private final UserRepository users;
    private final MerchantProfiles merchantProfiles;
    private final SessionStateCache sessions;
    private final VerificationCodeService codes;
    private final Clock clock;

    @Autowired
    public AdminUserService(UserRepository users, MerchantProfiles merchantProfiles, SessionStateCache sessions,
                            VerificationCodeService codes) {
        this(users, merchantProfiles, sessions, codes, Clock.systemUTC());
    }

    AdminUserService(UserRepository users, MerchantProfiles merchantProfiles, SessionStateCache sessions,
                     VerificationCodeService codes, Clock clock) {
        this.users = users;
        this.merchantProfiles = merchantProfiles;
        this.sessions = sessions;
        this.codes = codes;
        this.clock = clock;
    }

    /** The criteria of GET /admin/users. Every field may be null (no filter); {@code sort} null means NEWEST. */
    public record UserSearch(String q, Role role, UserStatusFilter status, LocalDate registeredFrom,
                             LocalDate registeredTo, UserSort sort) {

        /** The search text is personal data: keep it out of logs. */
        @Override
        public String toString() {
            return "UserSearch[role=" + role + ", status=" + status + ", registeredFrom=" + registeredFrom
                    + ", registeredTo=" + registeredTo + ", sort=" + sort + "]";
        }
    }

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> list(UserSearch search, int page, int size) {
        if (search.registeredFrom() != null && search.registeredTo() != null
                && search.registeredFrom().isAfter(search.registeredTo())) {
            throw new FieldValidationException("registeredFrom", "The start date must be on or before the end date.");
        }
        UserSort sort = search.sort() == null ? UserSort.NEWEST : search.sort();
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                sort.sort());
        return users.findAll(specification(search), pageRequest).map(AdminUserResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse get(UUID id) {
        User user = users.findById(id).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return detail(user);
    }

    @Transactional(readOnly = true)
    public UserStatsResponse stats() {
        Instant since = clock.instant().minus(NEW_USER_PERIOD);
        return new UserStatsResponse(
                users.countByStatusNot(UserStatus.REMOVED),
                users.countByStatus(UserStatus.ACTIVE),
                users.countByStatus(UserStatus.DISABLED),
                users.countByStatusIn(PENDING),
                users.countByStatusNotAndCreatedAtGreaterThanEqual(UserStatus.REMOVED, since));
    }

    /**
     * Updates name, email and phone. 409 EMAIL_ALREADY_REGISTERED / PHONE_ALREADY_REGISTERED when another account
     * has them; 409 ACCOUNT_REMOVED for a removed account. A new email ends the user's sessions and unlinks Google.
     */
    @Transactional
    public AdminUserDetailResponse update(UUID id, UpdateUserRequest request, UUID adminId) {
        User user = lock(id);
        ensureNotRemoved(user);

        String email = UserService.normalizeEmail(request.email());
        String phone = PhoneNumberNormalizer.normalize(request.phone())
                .orElseThrow(() -> new IllegalArgumentException("phone was not validated"));
        users.findByEmail(email).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        });
        users.findByPhone(phone).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ConflictException(ErrorCode.PHONE_ALREADY_REGISTERED);
        });

        boolean emailChanged = user.updateContactDetails(request.fullName().trim(), email, phone);
        if (emailChanged) {
            codes.invalidateAll(id, VerificationPurpose.PASSWORD_RESET);
            sessions.evict(id);
        }
        User saved = users.saveAndFlush(user);
        log.info("Admin {} updated user {} (role {}, emailChanged={})", adminId, id, saved.getRole(), emailChanged);
        return detail(saved);
    }

    /**
     * BLOCKED: an ACTIVE account becomes DISABLED and its sessions end at once. ACTIVE: a DISABLED account becomes
     * ACTIVE. Asking for the status the account already has changes nothing.
     *
     * @throws ConflictException CANNOT_CHANGE_OWN_ACCOUNT when the admin targets their own account;
     *                           ACCOUNT_REMOVED for a removed account; ACCOUNT_NOT_ACTIVE when blocking an account
     *                           that is not ACTIVE (e.g. pending); ACCOUNT_NOT_BLOCKED when unblocking an account that
     *                           is not DISABLED
     */
    @Transactional
    public AdminUserDetailResponse changeStatus(UUID id, UpdateUserStatusRequest.Change change, UUID adminId) {
        if (id.equals(adminId)) {
            throw new ConflictException(ErrorCode.CANNOT_CHANGE_OWN_ACCOUNT);
        }
        User user = lock(id);
        ensureNotRemoved(user);

        if (change == UpdateUserStatusRequest.Change.BLOCKED) {
            if (user.getStatus() == UserStatus.DISABLED) {
                return detail(user);
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new ConflictException(ErrorCode.ACCOUNT_NOT_ACTIVE);
            }
            user.block();
            codes.invalidateAll(id, VerificationPurpose.PASSWORD_RESET);
            sessions.evict(id);
            users.saveAndFlush(user);
            log.info("Admin {} blocked user {} (role {})", adminId, id, user.getRole());
        } else {
            if (user.getStatus() == UserStatus.ACTIVE) {
                return detail(user);
            }
            if (user.getStatus() != UserStatus.DISABLED) {
                throw new ConflictException(ErrorCode.ACCOUNT_NOT_BLOCKED);
            }
            user.unblock();
            sessions.evict(id);
            users.saveAndFlush(user);
            log.info("Admin {} unblocked user {} (role {})", adminId, id, user.getRole());
        }
        return detail(user);
    }

    // ---- Helpers ----

    private AdminUserDetailResponse detail(User user) {
        MerchantProfiles.Profile profile = user.getRole() == Role.MERCHANT
                ? merchantProfiles.find(user.getId()).orElse(null)
                : null;
        return AdminUserDetailResponse.from(user, profile);
    }

    private User lock(UUID id) {
        return users.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private static void ensureNotRemoved(User user) {
        if (user.getStatus() == UserStatus.REMOVED) {
            throw new ConflictException(ErrorCode.ACCOUNT_REMOVED);
        }
    }

    private static Specification<User> specification(UserSearch search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search.status() != null) {
                predicates.add(root.get("status").in(search.status().statuses()));
            } else {
                predicates.add(cb.notEqual(root.get("status"), UserStatus.REMOVED));
            }
            if (search.role() != null) {
                predicates.add(cb.equal(root.get("role"), search.role()));
            }
            if (search.registeredFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOf(search.registeredFrom())));
            }
            if (search.registeredTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), startOf(search.registeredTo().plusDays(1))));
            }
            String q = search.q() == null ? "" : search.q().trim();
            if (!q.isEmpty()) {
                Optional<UUID> id = parseUuid(q);
                if (id.isPresent()) {
                    predicates.add(cb.equal(root.get("id"), id.get()));
                } else {
                    String pattern = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
                    String phone = PhoneNumberNormalizer.normalize(q).orElse(q);
                    String phonePattern = "%" + escapeLike(phone.toLowerCase(Locale.ROOT)) + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                            cb.like(cb.lower(root.get("email")), pattern, '\\'),
                            cb.like(cb.lower(root.get("phone")), pattern, '\\'),
                            cb.like(cb.lower(root.get("phone")), phonePattern, '\\')));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(SRI_LANKA).toInstant();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value.length() == 36 ? Optional.of(UUID.fromString(value)) : Optional.empty();
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
