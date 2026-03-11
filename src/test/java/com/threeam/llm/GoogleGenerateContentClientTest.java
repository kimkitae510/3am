package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoogleGenerateContentClientTest {

    private final GoogleGenerateContentClient client =
            new GoogleGenerateContentClient(new ObjectMapper()) {
                @Override
                String endpoint() {
                    return "https://example.com";
                }

                @Override
                void authorize(HttpRequest.Builder builder) {
                }

                @Override
                long timeoutSeconds() {
                    return 1;
                }

                @Override
                String providerName() {
                    return "테스트";
                }
            };

    private static String body(String partsJson) {
        return "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":" + partsJson + "}}]}";
    }

    @Test
    @DisplayName("응답 파싱 - 단일 파트는 그대로 돌려준다")
    void parseSinglePart() {
        assertThat(client.parseBody(body("[{\"text\":\"안녕\"}]"))).isEqualTo("안녕");
    }

    @Test
    @DisplayName("응답 파싱 - 여러 파트로 쪼개진 텍스트를 전부 이어붙인다(첫 파트만 읽으면 JSON이 중간에 잘린다)")
    void parseJoinsSplitParts() {
        String split = body("[{\"text\":\"{\\\"verdict\\\":\"},{\"text\":\"\\\"POSSIBLE\\\"}\"}]");
        assertThat(client.parseBody(split)).isEqualTo("{\"verdict\":\"POSSIBLE\"}");
    }

    @Test
    @DisplayName("응답 파싱 - thought(추론 요약) 파트는 응답 본문에서 제외한다")
    void parseSkipsThoughtParts() {
        String withThought = body("[{\"thought\":true,\"text\":\"추론 과정\"},{\"text\":\"본문\"}]");
        assertThat(client.parseBody(withThought)).isEqualTo("본문");
    }

    @Test
    @DisplayName("응답 파싱 - 텍스트 파트가 하나도 없으면 LlmException")
    void parseFailsWithoutText() {
        assertThatThrownBy(() -> client.parseBody(body("[{\"thought\":true,\"text\":\"추론만\"}]")))
                .isInstanceOf(LlmException.class);
    }
}
