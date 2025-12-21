package com.threeam.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threeam.payment.client.PaymentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionReadinessGuardTest {

    private static final String GOOD_SECRET = "a-very-long-random-production-secret-value-32bytes+";

    private JwtProperties jwt(String secret) {
        JwtProperties props = new JwtProperties();
        props.setSecret(secret);
        return props;
    }

    private PaymentProperties payment(String provider, String clientKey, String secretKey) {
        PaymentProperties props = new PaymentProperties();
        props.setProvider(provider);
        props.getToss().setClientKey(clientKey);
        props.getToss().setSecretKey(secretKey);
        return props;
    }

    // 모든 프로바이더를 실연동 값으로 채운 "운영 정상" 환경을 만든다.
    private MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("payment.provider", "toss");
        env.setProperty("oauth.provider", "real");
        env.setProperty("mail.provider", "smtp");
        env.setProperty("llm.provider", "gemini");
        return env;
    }

    private ProductionReadinessGuard guard(JwtProperties jwt, PaymentProperties payment, MockEnvironment env) {
        return new ProductionReadinessGuard(jwt, payment, env);
    }

    @Test
    @DisplayName("운영 - JWT 시크릿이 폴백값 그대로면 부팅을 거부한다")
    void prodWithFallbackSecret_refusesBoot() {
        MockEnvironment env = prodEnv();
        ProductionReadinessGuard guard = guard(
                jwt("change-me-to-a-long-random-secret-key-at-least-256-bits"),
                payment("toss", "ck", "sk"), env);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("운영 - 결제/소셜/메일/LLM 중 하나라도 mock이면 부팅을 거부한다")
    void prodWithAnyMock_refusesBoot() {
        MockEnvironment env = prodEnv();
        env.setProperty("oauth.provider", "mock");
        ProductionReadinessGuard guard = guard(jwt(GOOD_SECRET), payment("toss", "ck", "sk"), env);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("소셜 로그인");
    }

    @Test
    @DisplayName("운영 - toss인데 시크릿 키가 비면 부팅을 거부한다")
    void prodTossMissingKey_refusesBoot() {
        MockEnvironment env = prodEnv();
        ProductionReadinessGuard guard = guard(jwt(GOOD_SECRET), payment("toss", "ck", ""), env);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_SECRET_KEY");
    }

    @Test
    @DisplayName("운영 - 모든 값이 실연동으로 채워지면 통과한다")
    void prodAllReal_passes() {
        ProductionReadinessGuard guard = guard(jwt(GOOD_SECRET), payment("toss", "ck", "sk"), prodEnv());

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("개발(비운영) - mock과 폴백 시크릿이어도 부팅은 통과한다(경고만)")
    void devWithMocks_passes() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payment.provider", "mock");
        env.setProperty("oauth.provider", "mock");
        env.setProperty("mail.provider", "mock");
        env.setProperty("llm.provider", "mock");
        ProductionReadinessGuard guard = guard(
                jwt("change-me-to-a-long-random-secret-key-at-least-256-bits"),
                payment("mock", "", ""), env);

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }
}
