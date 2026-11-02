/**
 * Shared kernel: small, no business logic, usable by every module.
 *
 * <ul>
 *   <li>{@code error}: {@link com.swiftdrop.shared.error.ApiError} (the API.md error shape),
 *       {@link com.swiftdrop.shared.error.ErrorCode} and the exceptions that produce them</li>
 *   <li>{@code web}: {@link com.swiftdrop.shared.web.CurrentUser}, {@link com.swiftdrop.shared.web.PageResponse},
 *       {@link com.swiftdrop.shared.web.PublicEndpoints}, {@link com.swiftdrop.shared.web.ApiPaths}</li>
 *   <li>{@code reference}: reference data for forms ({@link com.swiftdrop.shared.reference.District},
 *       {@link com.swiftdrop.shared.reference.ParcelsPerMonth})</li>
 *   <li>{@code validation}: Sri Lankan mobile number normaliser and its Bean Validation annotation</li>
 * </ul>
 *
 * <p>Owner: TBD (changes need agreement from the team, because every module depends on this).
 * Database schema: none. Events: none.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.swiftdrop.shared;

import org.springframework.modulith.ApplicationModule;
