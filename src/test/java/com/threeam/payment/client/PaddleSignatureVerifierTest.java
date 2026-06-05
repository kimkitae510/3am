package com.threeam.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 웹훅은 인증 없이 열린 경로다. 여기가 뚫리면 남이 "결제 완료" 알림을 쏴서
// 지급을 유도할 수 있으므로, 통과/차단 경계를 테스트로 못 박는다.
class PaddleSignatureVerifierTest {

    private static final String SECRET = "pdl_ntfset_test_secret";
    private static final String BODY = "{\"event_type\":\"transaction.completed\"}";

    private PaymentProperties properties;
    private PaddleSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        properties.getPaddle().setWebhookSecret(SECRET);
        verifier = new PaddleSignatureVerifier(properties);
    }

    @Test
    @DisplayName("패들이 보낸 서명은 통과한다")
    void acceptsValidSignature() {
        long ts = Instant.now().getEpochSecond();

        assertThat(verifier.verify(BODY, header(ts, sign(SECRET, ts + ":" + BODY)))).isTrue();
    }

    @Test
    @DisplayName("본문이 한 글자라도 바뀌면 거부한다")
    void rejectsTamperedBody() {
        long ts = Instant.now().getEpochSecond();
        String signature = header(ts, sign(SECRET, ts + ":" + BODY));

        assertThat(verifier.verify(BODY + " ", signature)).isFalse();
    }

    @Test
    @DisplayName("다른 키로 만든 서명은 거부한다")
    void rejectsWrongSecret() {
        long ts = Instant.now().getEpochSecond();

        assertThat(verifier.verify(BODY, header(ts, sign("other-secret", ts + ":" + BODY)))).isFalse();
    }

    @Test
    @DisplayName("오래된 서명은 거부한다(가로챈 요청 재전송 차단)")
    void rejectsStaleTimestamp() {
        long ts = Instant.now().getEpochSecond() - 3600;

        assertThat(verifier.verify(BODY, header(ts, sign(SECRET, ts + ":" + BODY)))).isFalse();
    }

    @Test
    @DisplayName("서명 헤더가 없으면 거부한다")
    void rejectsMissingHeader() {
        assertThat(verifier.verify(BODY, null)).isFalse();
        assertThat(verifier.verify(BODY, "")).isFalse();
        assertThat(verifier.verify(BODY, "ts=123")).isFalse();
    }

    @Test
    @DisplayName("시크릿 미설정이면 전부 거부한다 — 검증 없이 열어두지 않는다")
    void rejectsWhenSecretMissing() {
        properties.getPaddle().setWebhookSecret("");
        long ts = Instant.now().getEpochSecond();

        assertThat(verifier.verify(BODY, header(ts, sign(SECRET, ts + ":" + BODY)))).isFalse();
    }

    private String header(long ts, String hash) {
        return "ts=" + ts + ";h1=" + hash;
    }

    private String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
