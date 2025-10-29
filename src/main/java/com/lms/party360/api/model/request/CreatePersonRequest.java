package com.lms.party360.api.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreatePersonRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String dob,           // ISO date, validated downstream
        @NotBlank String ssn,           // validated/tokenized downstream
        @NotBlank String consentId,
        @NotNull Boolean asyncScreen,
        @NotBlank String tenant,
        @NotNull List<AddressInput> addresses,
        @NotNull List<ContactInput> contacts
) {}
