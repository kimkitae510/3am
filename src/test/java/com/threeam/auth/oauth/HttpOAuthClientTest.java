package com.threeam.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.entity.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// HTTP 호출부는 실연동 검증 영역이라 제외하고, 응답 파싱 규칙만 고정한다.
class HttpOAuthClientTest {

    @Test
    @DisplayName("카카오 프로필 - id는 숫자여도 문자열로, 이메일은 없으면 null")
    void parseKakao() throws Exception {
        String json = """
                {"id":123456789,"kakao_account":{"profile":{"nickname":"밤손님"}}}
                """;

        OAuthProfile profile = HttpOAuthClient.parseKakaoProfile(json);

        assertThat(profile.provider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(profile.providerId()).isEqualTo("123456789");
        assertThat(profile.email()).isNull(); // 기본 앱은 이메일 미제공이 정상 경로
    }

    @Test
    @DisplayName("네이버 프로필 - response 안의 id/email을 읽는다")
    void parseNaver() throws Exception {
        String json = """
                {"resultcode":"00","message":"success",
                 "response":{"id":"abcDEF123","nickname":"밤손님","email":"n@naver.com"}}
                """;

        OAuthProfile profile = HttpOAuthClient.parseNaverProfile(json);

        assertThat(profile.providerId()).isEqualTo("abcDEF123");
        assertThat(profile.email()).isEqualTo("n@naver.com");
    }

    @Test
    @DisplayName("토큰 응답 - access_token이 없으면 OAUTH_FAILED")
    void parseAccessToken_missing() {
        assertThatThrownBy(() -> HttpOAuthClient.parseAccessToken("{\"error\":\"invalid_grant\"}"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> HttpOAuthClient.parseNaverProfile("{\"resultcode\":\"024\"}"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("토큰 응답 - access_token을 추출한다")
    void parseAccessToken() throws Exception {
        assertThat(HttpOAuthClient.parseAccessToken("{\"access_token\":\"tok\",\"token_type\":\"bearer\"}"))
                .isEqualTo("tok");
    }
}
