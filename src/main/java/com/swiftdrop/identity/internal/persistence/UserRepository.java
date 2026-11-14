package com.swiftdrop.identity.internal.persistence;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.shared.reference.District;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    Optional<User> findByGoogleSub(String googleSub);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    List<User> findByRoleAndStatusOrderByFullNameAsc(Role role, UserStatus status);

    List<User> findByRoleAndStatusAndBranchOrderByFullNameAsc(Role role, UserStatus status, District branch);

    /** Accounts per role, leaving out those with {@code excluded} status (the admin overview passes REMOVED). */
    @Query("select u.role as role, count(u) as total from User u where u.status <> :excluded group by u.role")
    List<RoleTotal> countPerRoleExcluding(UserStatus excluded);

    long countByStatus(UserStatus status);

    long countByStatusNot(UserStatus status);

    long countByStatusIn(Collection<UserStatus> statuses);

    long countByStatusNotAndCreatedAtGreaterThanEqual(UserStatus status, Instant since);

    /** The newest accounts first, leaving out those with {@code excluded} status. */
    List<User> findByStatusNotOrderByCreatedAtDesc(UserStatus excluded, Limit limit);

    /** Row lock, so two parallel requests for the same user run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(UUID id);
}
