package com.neobank.identityservice.dto;

import com.neobank.identityservice.model.Role;
import com.neobank.identityservice.model.UserStatus;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {
    private UUID id;
    private String email;
    private Role role;
    private UserStatus status;
    private String firstName;
    private String lastName;
    private OffsetDateTime createdAt;
}