package com.swiftdrop.identity.internal.persistence;

import com.swiftdrop.identity.Role;

/** One row of {@link UserRepository#countPerRoleExcluding}: a role and how many accounts have it. */
public interface RoleTotal {

    Role getRole();

    long getTotal();
}
