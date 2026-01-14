package com.threeam.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.entity.AuthProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class HttpOAuthClient implements OAuthClient {

    private static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_PROFILE_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String NAVER_TOKEN_URL = "https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_PROFILE_URL = "https://openapi.naver.com/v1/nid/me";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OAuthProperties properties;
    private final HttpClient httpClient;

    public HttpOAuthClient(OAuthProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public OAuthProfile fetchProfile(AuthProvider provider, String code, String state, String redirectUri) {
        try {
            String accessToken = exchangeToken(provider, code, state, redirectUri);
            String profileJson = requestProfile(provider, accessToken);
            return provider == AuthProvider.KAKAO
                    ? parseKakaoProfile(profileJson)
                    : parseNaverProfile(profileJson);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("소셜 로그인 처리 실패 provider={}", provider, e);
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }

    private String exchangeToken(AuthProvider provider, String code, String state, String redirectUri)
            throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        if (provider == AuthProvider.KAKAO) {
            form.put("client_id", properties.getKakao().getClientId());
            form.put("redirect_uri", redirectUri);
            if (!properties.getKakao().getClientSecret().isBlank()) {
                form.put("client_secret", properties.getKakao().getClientSecret());
            }
        } else {
            form.put("client_id", properties.getNaver().getClientId());
            form.put("client_secret", properties.getNaver().getClientSecret());
            form.put("state", state == null ? "" : state);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(provider == AuthProvider.KAKAO ? KAKAO_TOKEN_URL : NAVER_TOKEN_URL))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // 만료/재사용된 인가 코드 등. 코드 원문은 로그에 남기지 않는다.
            log.warn("OAuth 토큰 교환 거절 provider={} status={} body={}", provider,
                    response.statusCode(), response.body());
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        return parseAccessToken(response.body());
    }

    private String requestProfile(AuthProvider provider, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(provider == AuthProvider.KAKAO ? KAKAO_PROFILE_URL : NAVER_PROFILE_URL))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("OAuth 프로필 조회 거절 provider={} status={}", provider, response.statusCode());
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        return response.body();
    }

    static String parseAccessToken(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json).path("access_token");
        if (node.isMissingNode() || node.asText().isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        return node.asText();
    }

    static OAuthProfile parseKakaoProfile(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode id = root.path("id");
        if (id.isMissingNode()) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        JsonNode account = root.path("kakao_account");
        String email = account.path("email").asText(null);
        return new OAuthProfile(AuthProvider.KAKAO, id.asText(), email);
    }

    static OAuthProfile parseNaverProfile(String json) throws Exception {
        JsonNode response = MAPPER.readTree(json).path("response");
        String id = response.path("id").asText(null);
        if (id == null) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        String email = response.path("email").asText(null);
        return new OAuthProfile(AuthProvider.NAVER, id, email);
    }

    private String encodeForm(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
