package com.swiftdrop.users.domain;

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
@Table(name = "users")
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

    /** Null for accounts created with Google; they cannot log in with a password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** Google's account id (OIDC "sub"), set once the user signs in with Google. */
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

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
