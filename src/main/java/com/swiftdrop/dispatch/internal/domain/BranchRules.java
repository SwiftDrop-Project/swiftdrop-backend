package com.swiftdrop.dispatch.internal.domain;

import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;

/**
 * Which branches each assignment type needs (the same rule as the CHECK ck_courier_assignments_branches):
 * PICKUP has a destination branch and no origin; TRANSFER has both, and they differ; DELIVERY has an origin branch
 * and no destination (failed parcels go back to the origin).
 */
public final class BranchRules {

    private BranchRules() {
    }

    /** @throws FieldValidationException originBranch / destinationBranch when the branches don't fit the type */
    public static void check(AssignmentType type, District originBranch, District destinationBranch) {
        switch (type) {
            case PICKUP -> {
                if (destinationBranch == null) {
                    throw new FieldValidationException("destinationBranch",
                            "Select the branch the parcels go to.");
                }
                if (originBranch != null) {
                    throw new FieldValidationException("originBranch", "A pickup starts at the senders, not a branch.");
                }
            }
            case TRANSFER -> {
                if (originBranch == null) {
                    throw new FieldValidationException("originBranch", "Select the branch the parcels leave from.");
                }
                if (destinationBranch == null) {
                    throw new FieldValidationException("destinationBranch",
                            "Select the branch the parcels go to.");
                }
                if (originBranch == destinationBranch) {
                    throw new FieldValidationException("destinationBranch",
                            "Select a different branch from the one the parcels leave from.");
                }
            }
            case DELIVERY -> {
                if (originBranch == null) {
                    throw new FieldValidationException("originBranch", "Select the branch the parcels leave from.");
                }
                if (destinationBranch != null) {
                    throw new FieldValidationException("destinationBranch",
                            "A delivery ends at the recipients, not a branch.");
                }
            }
        }
    }
}
