package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VertexAiLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VertexAiProperties properties(String location) {
        VertexAiProperties props = new VertexAiProperties();
        props.setProjectId("my-project");
        props.setLocation(location);
        props.setModel("gemini-2.5-flash");
        return props;
    }

    @Test
    @DisplayName("global 로케이션이면 리전 접두사 없는 호스트로 조립된다")
    void endpointGlobal() {
        assertThat(properties("global").endpoint()).isEqualTo(
                "https://aiplatform.googleapis.com/v1/projects/my-project"
                        + "/locations/global/publishers/google/models/gemini-2.5-flash:generateContent");
    }

    @Test
    @DisplayName("리전 로케이션이면 호스트에 리전 접두사가 붙는다")
    void endpointRegional() {
        assertThat(properties("asia-northeast3").endpoint()).isEqualTo(
                "https://asia-northeast3-aiplatform.googleapis.com/v1/projects/my-project"
                        + "/locations/asia-northeast3/publishers/google/models/gemini-2.5-flash:generateContent");
    }

    @Test
    @DisplayName("진단 전용 모델이 비어 있으면 기본 모델 엔드포인트로 떨어진다")
    void assessmentEndpointFallsBack() {
        assertThat(properties("global").assessmentEndpoint())
                .isEqualTo(properties("global").endpoint());
    }

    @Test
    @DisplayName("진단 전용 모델을 지정하면 진단 엔드포인트만 그 모델을 쓴다")
    void assessmentEndpointUsesOverride() {
        VertexAiProperties props = properties("global");
        props.setAssessmentModel("gemini-2.5-pro");

        assertThat(props.assessmentEndpoint()).contains("/models/gemini-2.5-pro:generateContent");
        assertThat(props.endpoint()).contains("/models/gemini-2.5-flash:generateContent");
    }

    // GoogleCredentials는 핵심 메서드가 final이라 Mockito 목이 안 걸린다.
    // 실제 객체에 고정 토큰을 넣어 진짜 만료/갱신 로직 위에서 검증한다.

    @Test
    @DisplayName("authorize는 액세스 토큰을 Bearer 헤더로 싣는다")
    void authorizeSetsBearerToken() {
        AccessToken valid = new AccessToken("test-token", new Date(System.currentTimeMillis() + 3_600_000));
        VertexAiLlmClient client =
                new VertexAiLlmClient(properties("global"), objectMapper, GoogleCredentials.create(valid));

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));
        client.authorize(builder);

        assertThat(builder.build().headers().firstValue("Authorization"))
                .hasValue("Bearer test-token");
    }

    @Test
    @DisplayName("토큰 갱신 실패는 LlmException으로 감싼다 — 전역 핸들러가 502로 응답하게")
    void authorizeWrapsRefreshFailure() {
        AccessToken expired = new AccessToken("stale-token", new Date(System.currentTimeMillis() - 1_000));
        GoogleCredentials credentials = new GoogleCredentials(expired) {
            @Override
            public AccessToken refreshAccessToken() throws IOException {
                throw new IOException("refresh failed");
            }
        };
        VertexAiLlmClient client = new VertexAiLlmClient(properties("global"), objectMapper, credentials);

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));
        assertThatThrownBy(() -> client.authorize(builder)).isInstanceOf(LlmException.class);
    }
}
