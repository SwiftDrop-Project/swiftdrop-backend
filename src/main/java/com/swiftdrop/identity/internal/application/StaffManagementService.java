package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.dto.AdminResetPasswordRequest;
import com.swiftdrop.identity.internal.dto.CourierDetailResponse;
import com.swiftdrop.identity.internal.dto.CreateCourierRequest;
import com.swiftdrop.identity.internal.dto.CreateDispatcherRequest;
import com.swiftdrop.identity.internal.dto.DispatcherDetailResponse;
import com.swiftdrop.identity.internal.dto.UpdateCourierRequest;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin provisioning and lifecycle management for internal staff (couriers and dispatchers).
 *
 * <p>Security invariants:
 * <ul>
 *   <li>BCrypt password hashing runs before acquiring a database transaction or row lock.</li>
 *   <li>Mutating operations lock the target user row ({@code PESSIMISTIC_WRITE}) so concurrent requests cannot race
 *       or overwrite newer security state.</li>
 *   <li>Removed accounts ({@link UserStatus#REMOVED}) are terminal and cannot be edited, unblocked or reset.</li>
 *   <li>Logs record only actor/target UUIDs and non-PII attributes (never emails, phones, names or passwords).</li>
 * </ul>
 */
@Service
public class StaffManagementService {

    private static final Logger log = LoggerFactory.getLogger(StaffManagementService.class);
    static final int MAX_PAGE_SIZE = 100;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionStateCache sessions;
    private final VerificationCodeService codes;
    private final TransactionTemplate transactions;

    public StaffManagementService(UserRepository users,
                                  PasswordEncoder passwordEncoder,
                                  SessionStateCache sessions,
                                  VerificationCodeService codes,
                                  TransactionTemplate transactions) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.codes = codes;
        this.transactions = transactions;
    }

    // ---- Couriers ----

    public CourierDetailResponse createCourier(CreateCourierRequest request, UUID adminId) {
        String email = UserService.normalizeEmail(request.email());
        String phone = normalizePhone(request.phone());
        String passwordHash = passwordEncoder.encode(request.password());

        CourierDetailResponse created = Objects.requireNonNull(transactions.execute(tx -> {
            ensureUniqueEmail(email, null);
            ensureUniquePhone(phone, null);
            User courier = User.staffCourier(request.fullName().trim(), email, phone, passwordHash,
                    request.branch(), request.vehicleType());
            return CourierDetailResponse.from(users.saveAndFlush(courier));
        }));
        log.info("Admin {} created courier {} in branch {} with vehicle {}",
                adminId, created.id(), created.branch(), created.vehicleType());
        return created;
    }

    @Transactional(readOnly = true)
    public Page<CourierDetailResponse> listCouriers(String q, District branch, UserStatus status, Pageable pageable) {
        Specification<User> spec = staffSpecification(Role.COURIER, q, branch, status);
        return users.findAll(spec, cappedPage(pageable)).map(CourierDetailResponse::from);
    }

    @Transactional(readOnly = true)
    public CourierDetailResponse getCourier(UUID courierId) {
        User courier = users.findById(courierId)
                .filter(u -> u.getRole() == Role.COURIER)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return CourierDetailResponse.from(courier);
    }

    @Transactional
    public CourierDetailResponse updateCourier(UUID courierId, UpdateCourierRequest request, UUID adminId) {
        User courier = lockStaff(courierId, Role.COURIER);
        ensureNotRemoved(courier);

        String email = UserService.normalizeEmail(request.email());
        String phone = normalizePhone(request.phone());
        ensureUniqueEmail(email, courierId);
        ensureUniquePhone(phone, courierId);

        boolean emailChanged = courier.updateCourierDetails(
                request.fullName().trim(),
                email,
                phone,
                request.branch(),
                request.vehicleType());
        if (emailChanged) {
            codes.invalidateAll(courierId, VerificationPurpose.PASSWORD_RESET);
            sessions.evict(courierId);
        }
        User saved = users.saveAndFlush(courier);
        log.info("Admin {} updated courier {} in branch {} (emailChanged={})",
                adminId, courierId, saved.getBranch(), emailChanged);
        return CourierDetailResponse.from(saved);
    }

    @Transactional
    public CourierDetailResponse blockCourier(UUID courierId, UUID adminId) {
        User courier = lockStaff(courierId, Role.COURIER);
        ensureNotRemoved(courier);
        if (courier.getStatus() != UserStatus.DISABLED) {
            courier.block();
            codes.invalidateAll(courierId, VerificationPurpose.PASSWORD_RESET);
            sessions.evict(courierId);
            users.saveAndFlush(courier);
            log.info("Admin {} blocked courier {}", adminId, courierId);
        }
        return CourierDetailResponse.from(courier);
    }

    @Transactional
    public CourierDetailResponse unblockCourier(UUID courierId, UUID adminId) {
        User courier = lockStaff(courierId, Role.COURIER);
        ensureNotRemoved(courier);
        if (courier.getStatus() == UserStatus.DISABLED) {
            courier.unblock();
            sessions.evict(courierId);
            users.saveAndFlush(courier);
            log.info("Admin {} unblocked courier {}", adminId, courierId);
        }
        return CourierDetailResponse.from(courier);
    }

    @Transactional
    public CourierDetailResponse removeCourier(UUID courierId, UUID adminId) {
        User courier = lockStaff(courierId, Role.COURIER);
        if (courier.getStatus() != UserStatus.REMOVED) {
            courier.remove(Instant.now());
            codes.invalidateAll(courierId, VerificationPurpose.PASSWORD_RESET);
            sessions.evict(courierId);
            users.saveAndFlush(courier);
            log.info("Admin {} removed courier {}", adminId, courierId);
        }
        return CourierDetailResponse.from(courier);
    }

    public CourierDetailResponse resetCourierPassword(UUID courierId, AdminResetPasswordRequest request, UUID adminId) {
        String passwordHash = passwordEncoder.encode(request.newPassword());
        CourierDetailResponse updated = Objects.requireNonNull(transactions.execute(tx -> {
            User courier = lockStaff(courierId, Role.COURIER);
            ensureNotRemoved(courier);
            courier.resetStaffPassword(passwordHash);
            codes.invalidateAll(courierId, VerificationPurpose.PASSWORD_RESET);
            sessions.evict(courierId);
            return CourierDetailResponse.from(users.saveAndFlush(courier));
        }));
        log.info("Admin {} reset password for courier {}", adminId, courierId);
        return updated;
    }

    // ---- Dispatchers ----

    public DispatcherDetailResponse createDispatcher(CreateDispatcherRequest request, UUID adminId) {
        String email = UserService.normalizeEmail(request.email());
        String phone = normalizePhone(request.phone());
        String passwordHash = passwordEncoder.encode(request.password());

        DispatcherDetailResponse created = Objects.requireNonNull(transactions.execute(tx -> {
            ensureUniqueEmail(email, null);
            ensureUniquePhone(phone, null);
            User dispatcher = User.staffDispatcher(request.fullName().trim(), email, phone, passwordHash,
                    request.branch());
            return DispatcherDetailResponse.from(users.saveAndFlush(dispatcher));
        }));
        log.info("Admin {} created dispatcher {} in branch {}", adminId, created.id(), created.branch());
        return created;
    }

    @Transactional(readOnly = true)
    public Page<DispatcherDetailResponse> listDispatchers(String q, District branch, Pageable pageable) {
        Specification<User> spec = staffSpecification(Role.DISPATCHER, q, branch, null);
        return users.findAll(spec, cappedPage(pageable)).map(DispatcherDetailResponse::from);
    }

    @Transactional(readOnly = true)
    public DispatcherDetailResponse getDispatcher(UUID dispatcherId) {
        User dispatcher = users.findById(dispatcherId)
                .filter(u -> u.getRole() == Role.DISPATCHER)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return DispatcherDetailResponse.from(dispatcher);
    }

    // ---- Helpers ----

    private User lockStaff(UUID id, Role expectedRole) {
        return users.findByIdForUpdate(id)
                .filter(u -> u.getRole() == expectedRole)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private static void ensureNotRemoved(User user) {
        if (user.getStatus() == UserStatus.REMOVED) {
            throw new ConflictException(ErrorCode.ACCOUNT_REMOVED);
        }
    }

    private void ensureUniqueEmail(String normalizedEmail, UUID excludeId) {
        users.findByEmail(normalizedEmail)
                .filter(existing -> !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED);
                });
    }

    private void ensureUniquePhone(String normalizedPhone, UUID excludeId) {
        users.findByPhone(normalizedPhone)
                .filter(existing -> !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    throw new ConflictException(ErrorCode.PHONE_ALREADY_REGISTERED);
                });
    }

    private static Specification<User> staffSpecification(Role role, String q, District branch, UserStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("role"), role));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            } else {
                predicates.add(cb.notEqual(root.get("status"), UserStatus.REMOVED));
            }
            if (branch != null) {
                predicates.add(cb.equal(root.get("branch"), branch));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                String phoneCandidate = PhoneNumberNormalizer.normalize(q.trim()).orElse(q.trim());
                String phonePattern = "%" + escapeLike(phoneCandidate.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("email")), pattern, '\\'),
                        cb.like(cb.lower(root.get("phone")), pattern, '\\'),
                        cb.like(cb.lower(root.get("phone")), phonePattern, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Pageable cappedPage(Pageable requested) {
        int page = requested.isUnpaged() ? 0 : Math.max(0, requested.getPageNumber());
        int size = requested.isUnpaged() ? 20 : Math.min(Math.max(1, requested.getPageSize()), MAX_PAGE_SIZE);
        return PageRequest.of(page, size, NEWEST_FIRST);
    }

    private static String normalizePhone(String rawPhone) {
        return PhoneNumberNormalizer.normalize(rawPhone)
                .orElseThrow(() -> new IllegalArgumentException("phone was not validated"));
    }
}

