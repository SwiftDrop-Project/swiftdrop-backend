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
 * <p>Dependencies: delivery (parcels, {@code FailureReason}, {@code ParcelStatusChanged}), identity (active
 * couriers, {@code requireAssignableCourier}), shared. Delivery never depends on dispatch, and dispatch never depends
 * on courier.
 *
 * <p>Owner: D.M.A.I. Disanayaka (WP8). Contract owned by dispatch; agreed with WP9 (courier). Events published: none
 * yet (assignment events for the live board come with tracking). Events consumed: {@code ParcelStatusChanged} from
 * RabbitMQ (queue {@code dispatch.q}), to keep the pickup queue.
 *
 * <p>Status: {@code AssignmentApi} is implemented ({@code internal.application.AssignmentService}). Dispatcher
 * endpoints under {@code /dispatch} (roles DISPATCHER and ADMIN, see API.md "Dispatch"): parcel queues, courier tasks
 * (create, add or remove parcels, reassign, cancel), the one-step parcel assignment, courier workloads, overview and
 * delivery issues.
 *
 * <p>Database schema {@code dispatch} (no foreign keys; courier, dispatcher and parcel ids are plain UUIDs):
 * <ul>
 *   <li>{@code dispatch.courier_assignments}: one row per assignment. CHECKs keep the branch rule per type (PICKUP:
 *       destination; TRANSFER: both, different; DELIVERY: origin) and the timestamps in step with the status.
 *       {@code updated_at} changes on every change, an item outcome included, so {@code version} always goes up.</li>
 *   <li>{@code dispatch.assignment_items}: one row per parcel in an assignment. The partial unique index
 *       {@code uk_assignment_items_open_parcel} on {@code (parcel_id) WHERE closed_at IS NULL} keeps a parcel open in
 *       at most one assignment, even when two dispatchers assign it at the same moment.</li>
 *   <li>{@code dispatch.pickup_queue}: parcels waiting for pickup (CREATED) by the sender's district, kept from the
 *       {@code ParcelStatusChanged} events because delivery has no such query. Ids, tracking number and district
 *       only.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Dispatch")
package com.swiftdrop.dispatch;

import org.springframework.modulith.ApplicationModule;
