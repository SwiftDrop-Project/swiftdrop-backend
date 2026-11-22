-- delivery module: branches (multi-branch workflow, WP9 requirements).
-- A parcel can now wait at a SwiftDrop branch (status AT_BRANCH) between pickup, transfers and final delivery.
-- The branch is a District name (one branch per district), like pickup_district and recipient_district.
-- Additive only: two new nullable columns, one new CHECK, one new index, and the status CHECKs widened to allow
-- AT_BRANCH (dropped and added again; every row that was valid before is still valid). Nothing is removed.

-- ---- parcels ----

-- the branch the parcel is at; set only while AT_BRANCH
ALTER TABLE delivery.parcels ADD COLUMN current_branch VARCHAR(30);

ALTER TABLE delivery.parcels DROP CONSTRAINT ck_parcels_status;
ALTER TABLE delivery.parcels ADD CONSTRAINT ck_parcels_status
    CHECK (status IN ('CREATED', 'PICKED_UP', 'AT_BRANCH', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'));

ALTER TABLE delivery.parcels ADD CONSTRAINT ck_parcels_current_branch
    CHECK ((current_branch IS NOT NULL) = (status = 'AT_BRANCH'));

-- parcels waiting at a branch, oldest first (findAtBranch, for dispatch)
CREATE INDEX idx_parcels_at_branch ON delivery.parcels (current_branch, status_changed_at)
    WHERE status = 'AT_BRANCH';

-- ---- parcel_status_history ----

-- only for AT_BRANCH: the branch the parcel arrived at
ALTER TABLE delivery.parcel_status_history ADD COLUMN branch VARCHAR(30);

ALTER TABLE delivery.parcel_status_history DROP CONSTRAINT ck_parcel_status_history_to_status;
ALTER TABLE delivery.parcel_status_history ADD CONSTRAINT ck_parcel_status_history_to_status
    CHECK (to_status IN ('CREATED', 'PICKED_UP', 'AT_BRANCH', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'));

ALTER TABLE delivery.parcel_status_history DROP CONSTRAINT ck_parcel_status_history_from_status;
ALTER TABLE delivery.parcel_status_history ADD CONSTRAINT ck_parcel_status_history_from_status
    CHECK (from_status IN ('CREATED', 'PICKED_UP', 'AT_BRANCH', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'));
