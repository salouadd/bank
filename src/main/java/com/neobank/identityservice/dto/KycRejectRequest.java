package com.neobank.identityservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KycRejectRequest {

    @NotBlank(message = "Le motif de rejet est obligatoire")
    private String rejectionReason;
}