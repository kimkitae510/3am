package com.threeam.user.dto;

import com.threeam.user.entity.User;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserMeResponse {

    // 문의 대응용 회원번호 — 유저가 자기 식별자(특히 소셜 계정)를 알 방법이 없어 이걸 노출한다
    private final Long id;
    // 소셜 가입은 이메일 미제공일 수 있어 null 가능
    private final String email;
    private final String provider;

    public static UserMeResponse from(User user) {
        return new UserMeResponse(user.getId(), user.getEmail(), user.getProvider().name());
    }
}
