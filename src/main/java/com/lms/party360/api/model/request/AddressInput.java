package com.lms.party360.api.model.request;

import jakarta.validation.constraints.NotBlank;

public record AddressInput(
        @NotBlank String type, // MAILING/PHYSICAL/WORK
        @NotBlank String line1,
        String line2,
        @NotBlank String city,
        @NotBlank String state,
        @NotBlank String postalCode,
        String country
) {}
