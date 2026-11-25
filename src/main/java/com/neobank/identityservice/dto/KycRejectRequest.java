package com.neobank.identityservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = 1000, message = "Le motif de rejet ne peut pas dépasser 1000 caractères")
    private String rejectionReason;
}