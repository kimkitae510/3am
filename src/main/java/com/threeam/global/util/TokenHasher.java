package com.threeam.global.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 저장용 단방향 해시. refresh 토큰, 인증 코드처럼 "제시한 값과 대조만 하면 되는" 비밀을
// DB에 원문으로 두지 않기 위해 쓴다. BCrypt가 아니라 SHA-256인 이유: 이 값들은
// 고엔트로피(JWT 서명 문자열, SecureRandom 코드)라 무차별 대입 대상이 아니고,
// 대조가 잦아(매 재발급) 저비용 해시가 맞다. 비밀번호(저엔트로피)는 BCrypt를 계속 쓴다.
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
