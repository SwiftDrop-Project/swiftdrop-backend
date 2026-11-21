-- delivery module: proof-of-delivery photos (agreed with WP9, courier).
-- A courier uploads a photo of the delivery slip the recipient signed (DeliveryApi.storeProofPhoto); the image itself
-- lives in private file storage (Cloudinary in production), and this table keeps the durable reference to it, never
-- a URL. The photo is attached when the parcel is marked DELIVERED (parcels.proof_photo_id points here) and can never
-- be replaced after that. A photo that was uploaded but never attached is an orphan and is removed after a while.
-- Additive only; no foreign keys (project rule): parcel_id and proof_photo_id refer to each other by UUID.

CREATE TABLE delivery.proof_photos (
    id              UUID         PRIMARY KEY,
    -- the parcel the photo was uploaded for; it can only be attached to this parcel
    parcel_id       UUID         NOT NULL,
    -- identity user id of who uploaded it (the courier)
    uploaded_by     UUID         NOT NULL,
    -- which storage holds it (e.g. CLOUDINARY, MEMORY) and its key there (a Cloudinary public id). The key is made
    -- from the photo id only, so it never holds a name, address or tracking number.
    storage         VARCHAR(20)  NOT NULL,
    storage_key     VARCHAR(255) NOT NULL,
    content_type    VARCHAR(50)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    -- SHA-256 of the stored bytes, hex
    sha256          VARCHAR(64)  NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL,
    -- set in the same transaction as the parcel's DELIVERED change; null while the photo is not attached
    attached_at     TIMESTAMPTZ,
    CONSTRAINT uk_proof_photos_storage_key UNIQUE (storage, storage_key),
    CONSTRAINT ck_proof_photos_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_proof_photos_size CHECK (size_bytes > 0)
);

-- A parcel has at most one attached proof photo, even if two requests race.
CREATE UNIQUE INDEX uk_proof_photos_one_attached_per_parcel
    ON delivery.proof_photos (parcel_id) WHERE attached_at IS NOT NULL;

-- Finding a parcel's photos, and the orphans to clean up (oldest first).
CREATE INDEX idx_proof_photos_parcel_id ON delivery.proof_photos (parcel_id);
CREATE INDEX idx_proof_photos_unattached ON delivery.proof_photos (uploaded_at) WHERE attached_at IS NULL;
