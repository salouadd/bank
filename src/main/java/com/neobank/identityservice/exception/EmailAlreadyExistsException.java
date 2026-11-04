package com.neobank.identityservice.exception;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("Un compte avec l'adresse email '" + email + "' existe déjà");
    }
}