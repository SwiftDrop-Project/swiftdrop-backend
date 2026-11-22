package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.notifications.CustomerRecipientLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CustomerRecipientLookupAdapter
        implements CustomerRecipientLookup {

    private final IdentityApi identityApi;

    public CustomerRecipientLookupAdapter(IdentityApi identityApi) {
        this.identityApi = identityApi;
    }

    @Override
    public Optional<CustomerRecipient> findById(UUID customerId) {
        return identityApi.findById(customerId)
                .map(user -> new CustomerRecipient(
                        user.fullName(),
                        user.email()
                ));
    }
}
