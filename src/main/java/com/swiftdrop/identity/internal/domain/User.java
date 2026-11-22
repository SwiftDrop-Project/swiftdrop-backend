package com.swiftdrop.identity.internal.domain;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /** Always stored lower-case. */
    @Column(nullable = false, unique = true, length = 254)
    private String email;

    /** Always stored as +947XXXXXXXX. Null only while the status is PROFILE_INCOMPLETE. */
    @Column(unique = true, length = 12)
    private String phone;

    /** Null for accounts created with Google; they cannot log in with a password until they reset it. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** Google's account id (OIDC "sub"), set once the user signs in with Google. */
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserStatus status;

    /**
     * Copied into every token as the "ver" claim. A token whose version differs from this is rejected, so
     * incrementing it ends every existing session of the user.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    /** True for admin-created staff until they choose their own password, and after an admin password reset. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** Assigned district branch for couriers and dispatchers. */
    @Enumerated(EnumType.STRING)
    @Column(name = "branch", length = 30)
    private District branch;

    /** Assigned vehicle type for couriers. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 30)
    private VehicleType vehicleType;

    /** Timestamp when the account was soft-deleted (status = REMOVED). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String fullName, String email, String phone, String passwordHash, Role role, UserStatus status) {
        this.fullName = fullName;
        this.email = email.toLowerCase(Locale.ROOT);
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
    }

    /** Admin-provisioned courier: active, email trusted by admin creation, must change password on first login. */
    public static User staffCourier(String fullName, String email, String phone, String passwordHash,
                                    District branch, VehicleType vehicleType) {
        User user = new User(fullName, email, phone, passwordHash, Role.COURIER, UserStatus.ACTIVE);
        user.mustChangePassword = true;
        user.branch = branch;
        user.vehicleType = vehicleType;
        return user;
    }

    /** Admin-provisioned dispatcher: active, email trusted by admin creation, must change password on first login. */
    public static User staffDispatcher(String fullName, String email, String phone, String passwordHash,
                                       District branch) {
        User user = new User(fullName, email, phone, passwordHash, Role.DISPATCHER, UserStatus.ACTIVE);
        user.mustChangePassword = true;
        user.branch = branch;
        return user;
    }

    /** A user created from a Google sign-in: no password and no phone yet. */
    public static User fromGoogle(String googleSub, String fullName, String email, Role role, UserStatus status) {
        User user = new User(fullName, email, null, null, role, status);
        user.googleSub = googleSub;
        return user;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public void linkGoogle(String googleSub) {
        this.googleSub = googleSub;
    }

    /** Fills in what a Google sign-up did not provide. {@code fullName} null keeps the current name. */
    public void completeProfile(String fullName, String phone, Role role, UserStatus status) {
        if (fullName != null) {
            this.fullName = fullName;
        }
        this.phone = phone;
        this.role = role;
        this.status = status;
    }

    /** Sets a new BCrypt hash, clears any forced password-change flag, and ends every existing session. */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
        endSessions();
    }

    /** Sets an admin-issued replacement password, requires a change on next login, and ends every session. */
    public void resetStaffPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
        endSessions();
    }

    /**
     * Updates courier profile fields. If the login email changed, unlinks any Google identity bound to the previous
     * email and ends all existing sessions.
     *
     * @return true when the email address changed
     */
    public boolean updateCourierDetails(String fullName, String email, String phone, District branch,
                                        VehicleType vehicleType) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        boolean emailChanged = !this.email.equals(normalizedEmail);
        this.fullName = fullName;
        this.email = normalizedEmail;
        this.phone = phone;
        this.branch = branch;
        this.vehicleType = vehicleType;
        if (emailChanged) {
            this.googleSub = null;
            endSessions();
        }
        return emailChanged;
    }

    /**
     * Updates the name, email and phone (admin user management). Role, status, password and staff fields are not
     * touched. If the login email changed, unlinks any Google identity bound to the previous email and ends all
     * existing sessions.
     *
     * @return true when the email address changed
     */
    public boolean updateContactDetails(String fullName, String email, String phone) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        boolean emailChanged = !this.email.equals(normalizedEmail);
        this.fullName = fullName;
        this.email = normalizedEmail;
        this.phone = phone;
        if (emailChanged) {
            this.googleSub = null;
            endSessions();
        }
        return emailChanged;
    }

    /** Blocks the account and revokes all active sessions. */
    public void block() {
        this.status = UserStatus.DISABLED;
        endSessions();
    }

    /** Restores a blocked account to ACTIVE and bumps tokenVersion so no stale pre-block token works. */
    public void unblock() {
        this.status = UserStatus.ACTIVE;
        endSessions();
    }

    /** Soft-deletes the account permanently while keeping its UUID and profile history intact. */
    public void remove(Instant deletedAt) {
        this.status = UserStatus.REMOVED;
        this.deletedAt = deletedAt;
        this.googleSub = null;
        endSessions();
    }

    /** Removes the password (e.g. one set by someone who never proved they own the email) and ends every session. */
    public void removePassword() {
        this.passwordHash = null;
        endSessions();
    }

    /** Every token issued so far stops working. */
    public void endSessions() {
        this.tokenVersion++;
    }

    /**
     * The owner proved they control the email address. Only changes PENDING_EMAIL_VERIFICATION: customers become
     * ACTIVE, merchants PENDING_APPROVAL (they still wait for an admin). Returns whether the status changed.
     */
    public boolean markEmailVerified() {
        if (status != UserStatus.PENDING_EMAIL_VERIFICATION) {
            return false;
        }
        this.status = role == Role.MERCHANT ? UserStatus.PENDING_APPROVAL : UserStatus.ACTIVE;
        return true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
