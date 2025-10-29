package com.lms.party360.api.model.request;

import jakarta.validation.constraints.NotBlank;

public record ContactInput(
        @NotBlank String type, // EMAIL/PHONE/MOBILE
        @NotBlank String value
) {}
