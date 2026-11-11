-- dispatch module: courier assignments (multi-branch workflow, WP8). A dispatcher gives a courier an assignment
-- (PICKUP, TRANSFER or DELIVERY) holding one or more parcels; the courier records an outcome for each parcel.
-- courier_id, created_by and parcel_id are ids from identity and delivery, stored as plain UUIDs:
-- no foreign keys or joins across schemas (and, as everywhere in this project, no foreign keys at all).
-- Branches are District names (one branch per district), like delivery.parcels.current_branch.

CREATE SCHEMA IF NOT EXISTS dispatch;

CREATE TABLE dispatch.courier_assignments (
    id                 UUID         PRIMARY KEY,
    -- identity user id of the courier doing the work
    courier_id         UUID         NOT NULL,
    type               VARCHAR(20)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    -- where the parcels are taken from (TRANSFER, DELIVERY)
    origin_branch      VARCHAR(30),
    -- where the parcels are handed over (PICKUP, TRANSFER)
    destination_branch VARCHAR(30),
    -- the Sri Lanka day the work is planned for
    scheduled_for      DATE         NOT NULL,
    -- the dispatcher's note to the courier; may hold personal data, never log it
    note               VARCHAR(500),
    -- identity user id of the dispatcher (ADMIN) who created it
    created_by         UUID         NOT NULL,
    -- AssignmentView.assignedAt
    created_at         TIMESTAMPTZ  NOT NULL,
    -- the first item outcome
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    -- the last change of any kind, an item outcome included (so every change raises the version)
    updated_at         TIMESTAMPTZ  NOT NULL,
    -- optimistic locking (@Version); AssignmentView.version
    version            BIGINT       NOT NULL,
    CONSTRAINT ck_courier_assignments_type CHECK (type IN ('PICKUP', 'TRANSFER', 'DELIVERY')),
    CONSTRAINT ck_courier_assignments_status
        CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    -- PICKUP: destination only; TRANSFER: both, and different; DELIVERY: origin only (failed parcels go back there)
    CONSTRAINT ck_courier_assignments_branches CHECK (
        (type = 'PICKUP' AND origin_branch IS NULL AND destination_branch IS NOT NULL)
        OR (type = 'TRANSFER' AND origin_branch IS NOT NULL AND destination_branch IS NOT NULL
            AND origin_branch <> destination_branch)
        OR (type = 'DELIVERY' AND origin_branch IS NOT NULL AND destination_branch IS NULL)),
    CONSTRAINT ck_courier_assignments_completed CHECK ((completed_at IS NOT NULL) = (status = 'COMPLETED')),
    CONSTRAINT ck_courier_assignments_cancelled CHECK ((cancelled_at IS NOT NULL) = (status = 'CANCELLED')),
    -- nothing recorded yet while ASSIGNED; IN_PROGRESS and COMPLETED have a first outcome
    CONSTRAINT ck_courier_assignments_started CHECK (
        (status <> 'ASSIGNED' OR started_at IS NULL)
        AND (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL))
);

-- the courier's open assignments (AssignmentApi.findOpenForCourier)
CREATE INDEX idx_courier_assignments_courier_status ON dispatch.courier_assignments (courier_id, status);
-- the courier's history, newest close first (AssignmentApi.findClosedForCourier)
CREATE INDEX idx_courier_assignments_courier_closed
    ON dispatch.courier_assignments (courier_id, (COALESCE(completed_at, cancelled_at)) DESC);

CREATE TABLE dispatch.assignment_items (
    id             UUID         PRIMARY KEY,
    -- dispatch.courier_assignments.id (no foreign key)
    assignment_id  UUID         NOT NULL,
    -- delivery.parcels.id (no foreign key)
    parcel_id      UUID         NOT NULL,
    -- the order the dispatcher planned, from 1
    sequence       INTEGER      NOT NULL,
    outcome        VARCHAR(20)  NOT NULL,
    -- only for NOT_COLLECTED and NOT_LOADED
    problem        VARCHAR(30),
    -- only for FAILED, and kept on RETURNED
    failure_reason VARCHAR(30),
    -- the courier's last note; may hold personal data, never log it
    note           VARCHAR(500),
    -- who at the branch received the parcel (HANDED_OVER, RETURNED); personal data
    received_by    VARCHAR(100),
    -- who received the parcel (DELIVERED); personal data
    recipient_name VARCHAR(100),
    updated_at     TIMESTAMPTZ  NOT NULL,
    -- set when the outcome closes the item for the assignment's type (ItemOutcome.closes)
    closed_at      TIMESTAMPTZ,
    version        BIGINT       NOT NULL,
    CONSTRAINT uk_assignment_items_parcel UNIQUE (assignment_id, parcel_id),
    CONSTRAINT uk_assignment_items_sequence UNIQUE (assignment_id, sequence),
    CONSTRAINT ck_assignment_items_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_assignment_items_outcome CHECK (outcome IN ('PENDING', 'COLLECTED', 'LOADED', 'HANDED_OVER',
        'DELIVERED', 'FAILED', 'RETURNED', 'NOT_COLLECTED', 'NOT_LOADED')),
    CONSTRAINT ck_assignment_items_problem CHECK (problem IN ('SENDER_UNAVAILABLE', 'PARCEL_NOT_READY',
        'PARCEL_MISSING', 'PARCEL_DAMAGED', 'ADDRESS_UNREACHABLE', 'OTHER')),
    CONSTRAINT ck_assignment_items_problem_only_when_not_taken
        CHECK ((problem IS NOT NULL) = (outcome IN ('NOT_COLLECTED', 'NOT_LOADED'))),
    CONSTRAINT ck_assignment_items_failure_reason CHECK (failure_reason IN ('RECIPIENT_UNAVAILABLE',
        'WRONG_ADDRESS', 'RECIPIENT_REFUSED', 'ADDRESS_UNREACHABLE', 'PARCEL_DAMAGED', 'OTHER')),
    CONSTRAINT ck_assignment_items_failure_only_when_failed
        CHECK ((failure_reason IS NOT NULL) = (outcome IN ('FAILED', 'RETURNED'))),
    CONSTRAINT ck_assignment_items_recipient_only_when_delivered
        CHECK (recipient_name IS NULL OR outcome = 'DELIVERED'),
    CONSTRAINT ck_assignment_items_received_by_only_when_handed_over
        CHECK (received_by IS NULL OR outcome IN ('HANDED_OVER', 'RETURNED'))
);

-- A parcel is open in at most one assignment at a time, so two dispatchers can't give it to two couriers.
CREATE UNIQUE INDEX uk_assignment_items_open_parcel ON dispatch.assignment_items (parcel_id)
    WHERE closed_at IS NULL;
-- every assignment that ever held a parcel (courier history by tracking number, dispatcher views)
CREATE INDEX idx_assignment_items_parcel ON dispatch.assignment_items (parcel_id);
-- the items of an assignment are read by assignment_id; uk_assignment_items_parcel starts with it, so no extra index.
