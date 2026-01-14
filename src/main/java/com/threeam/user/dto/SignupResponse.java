package com.threeam.user.dto;

import com.threeam.user.entity.User;
import lombok.Getter;

@Getter
public class SignupResponse {

    private final Long id;
    private final String email;
    private SignupResponse(Long id, String email) {
        this.id = id;
        this.email = email;
    }

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail());
    }
}
