
package com.swiftdrop.settings.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_settings")
public class CompanySettings {

    @Id
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "support_email", nullable = false)
    private String supportEmail;

    @Column(name = "support_phone", nullable = false)
    private String supportPhone;

    @Column(name = "head_office_address", nullable = false)
    private String headOfficeAddress;

    @Column(name = "registration_number")
    private String registrationNumber;

    protected CompanySettings() {
    }

    public CompanySettings(
            Long id,
            String companyName,
            String supportEmail,
            String supportPhone,
            String headOfficeAddress,
            String registrationNumber) {
        this.id = id;
        this.companyName = companyName;
        this.supportEmail = supportEmail;
        this.supportPhone = supportPhone;
        this.headOfficeAddress = headOfficeAddress;
        this.registrationNumber = registrationNumber;
    }

    public Long getId() {
        return id;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getSupportEmail() {
        return supportEmail;
    }

    public String getSupportPhone() {
        return supportPhone;
    }

    public String getHeadOfficeAddress() {
        return headOfficeAddress;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }
}
