package com.threeam.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class OAuthSwitchConfirmRequest {

    @NotBlank(message = "전환 티켓은 필수입니다.")
    private String switchTicket;
}
