package com.lms.party360.api.model.response;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public record CreatePartyResponse(
        @NotBlank String partyId,
        @NotBlank String type,          // PERSON/BUSINESS
        @NotBlank String riskLevel,     // LOW/MEDIUM/HIGH
        @NotBlank String screeningStatus, // QUEUED/COMPLETED/PENDING
        String kycRequestId,
        String ofacRequestId,
        OffsetDateTime createdAt
) {}
