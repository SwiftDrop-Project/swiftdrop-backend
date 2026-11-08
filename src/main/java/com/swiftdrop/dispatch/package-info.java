/**
 * Dispatch: dispatchers give couriers assignments (pickup, transfer between branches, final delivery), each holding
 * one or more parcels, and track what happened to every parcel in them (multi-branch workflow, WP9 requirements).
 *
 * <p>Public API: {@link com.swiftdrop.dispatch.AssignmentApi} (the courier-facing part, used by WP9) and the types it
 * uses ({@link com.swiftdrop.dispatch.AssignmentView}, {@link com.swiftdrop.dispatch.AssignmentItemView},
 * {@link com.swiftdrop.dispatch.RecordOutcomeCommand}, {@link com.swiftdrop.dispatch.AssignmentType},
 * {@link com.swiftdrop.dispatch.AssignmentStatus}, {@link com.swiftdrop.dispatch.ItemOutcome} with the allowed
 * transitions per type, {@link com.swiftdrop.dispatch.TaskProblem}). Other modules may use only these top-level
 * types.
 *
 * <p>Dependencies: delivery (parcels, {@code FailureReason}), identity (active couriers, when implemented), shared.
 * Delivery never depends on dispatch, and dispatch never depends on courier.
 *
 * <p>Owner: TBD (WP8). Contract owned by dispatch; agreed with WP9 (courier). Events published: none yet (assignment
 * events for WP7 are deferred). Events consumed: none.
 *
 * <p>Status: contract only. Nothing implements {@code AssignmentApi} yet, there are no tables and no dispatcher
 * endpoints; the courier module answers 503 SERVICE_UNAVAILABLE until it is implemented, so mock it in tests.
 *
 * <p>Proposed tables (NOT created; for WP8 to confirm in its own migration, schema {@code dispatch}, no foreign
 * keys):
 * <ul>
 *   <li>{@code dispatch.courier_assignments}: {@code id} PK, {@code courier_id}, {@code type} CHECK,
 *       {@code status} CHECK, {@code origin_branch} and {@code destination_branch} VARCHAR(30),
 *       {@code scheduled_for} DATE, {@code note} VARCHAR(500), {@code created_by}, {@code created_at},
 *       {@code started_at}, {@code completed_at}, {@code cancelled_at}, {@code version}. CHECK the branch rules per
 *       type (PICKUP: destination; TRANSFER: both, different; DELIVERY: origin) and
 *       {@code (completed_at IS NOT NULL) = (status = 'COMPLETED')}. Indexes {@code (courier_id, status)} and
 *       {@code (courier_id, completed_at DESC)}.</li>
 *   <li>{@code dispatch.assignment_items}: {@code id} PK, {@code assignment_id}, {@code parcel_id},
 *       {@code sequence}, {@code outcome} CHECK, {@code problem} VARCHAR(30), {@code failure_reason} VARCHAR(30),
 *       {@code note} VARCHAR(500), {@code received_by} VARCHAR(100), {@code recipient_name} VARCHAR(100),
 *       {@code updated_at}, {@code closed_at}, {@code version}. {@code UNIQUE (assignment_id, parcel_id)}; a partial
 *       unique index on {@code (parcel_id) WHERE closed_at IS NULL}, so a parcel is open in at most one assignment;
 *       index on {@code (parcel_id)}.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Dispatch")
package com.swiftdrop.dispatch;

import org.springframework.modulith.ApplicationModule;
