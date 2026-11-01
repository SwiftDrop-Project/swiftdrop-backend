package com.swiftdrop.auth;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRegisterTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void registerCustomerReturns201AndLogsIn() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();

        MvcResult result = register(customerBody(email.toUpperCase(), phone.substring(0, 3) + " " + phone.substring(3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.fullName").value("Ayesha Fernando"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(stored(phone)))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant").isEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String cookie = setCookieHeader(result);
        assertThat(cookie).isNotNull().contains("HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Max-Age");
        tokenCookie(result);
    }

    @Test
    void registerMerchantReturns201WithoutCookieAndPendingApproval() throws Exception {
        String email = uniqueEmail();

        MvcResult result = register(merchantBody(email, uniquePhone()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.fullName").value("Nimal Perera"))
                .andExpect(jsonPath("$.merchant.businessName").value("Lanka Crafts"))
                .andExpect(jsonPath("$.merchant.pickupDistrict").value("COLOMBO"))
                .andExpect(jsonPath("$.merchant.city").value("Nugegoda"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("PENDING"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        assertThat(setCookieHeader(result)).isNull();
    }

    @Test
    void duplicateEmailReturns409EvenWithDifferentCase() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());

        register(customerBody(email.toUpperCase(), uniquePhone()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void duplicatePhoneReturns409EvenInAnotherFormat() throws Exception {
        String phone = uniquePhone();
        registerCustomer(uniqueEmail(), phone);

        register(merchantBody(uniqueEmail(), stored(phone)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void invalidCustomerFieldsReturn400WithFieldErrors() throws Exception {
        Map<String, Object> body = Map.of(
                "accountType", "CUSTOMER",
                "fullName", "A",
                "phone", "0112345678",
                "email", "name@example",
                "password", "onlyletters",
                "acceptTerms", false);

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Some fields need attention."))
                .andExpect(jsonPath("$.fieldErrors.fullName").value("Enter your full name."))
                .andExpect(jsonPath("$.fieldErrors.phone").value("Enter a Sri Lankan mobile number, like 077 123 4567."))
                .andExpect(jsonPath("$.fieldErrors.email").value("Enter a full email address, like name@example.com"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Use at least 8 characters, including a number."))
                .andExpect(jsonPath("$.fieldErrors.acceptTerms").value("Accept the terms to continue."));
    }

    @Test
    void invalidMerchantFieldsReturn400WithFieldErrors() throws Exception {
        Map<String, Object> body = merchantBody(uniqueEmail(), uniquePhone());
        body.put("businessName", "");
        body.put("businessRegNo", "X".repeat(31));
        body.remove("parcelsPerMonth");
        body.put("contactPerson", " ");
        body.put("city", "C");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.businessName").value("Enter your business name."))
                .andExpect(jsonPath("$.fieldErrors.businessRegNo").exists())
                .andExpect(jsonPath("$.fieldErrors.parcelsPerMonth").value("Select a range."))
                .andExpect(jsonPath("$.fieldErrors.contactPerson").value("Enter your full name."))
                .andExpect(jsonPath("$.fieldErrors.city").value("Enter your city or town."));
    }

    @Test
    void unknownDistrictReturns400() throws Exception {
        Map<String, Object> body = merchantBody(uniqueEmail(), uniquePhone());
        body.put("pickupDistrict", "ATLANTIS");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.pickupDistrict").value("Select a district."));
    }

    @Test
    void courierAndMissingAccountTypeCannotSelfRegister() throws Exception {
        Map<String, Object> courier = customerBody(uniqueEmail(), uniquePhone());
        courier.put("accountType", "COURIER");
        register(courier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.accountType").exists());

        Map<String, Object> missing = customerBody(uniqueEmail(), uniquePhone());
        missing.remove("accountType");
        register(missing)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.accountType").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonJsonBodyReturns415() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("accountType=CUSTOMER"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void passwordIsStoredAsBcryptHash() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());

        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);

        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }
}
