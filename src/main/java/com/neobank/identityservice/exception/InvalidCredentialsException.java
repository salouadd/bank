package com.neobank.identityservice.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Adresse email ou mot de passe incorrect");
    }
}