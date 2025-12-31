package com.threeam.mail;

public interface VerificationMailSender {

    void send(String email, String code);
}
