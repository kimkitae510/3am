package com.threeam.auth.oauth;

import com.threeam.user.entity.AuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 개발 스텁. 같은 code면 같은 providerId를 돌려줘 재로그인 흐름까지 키 없이 검증할 수 있다.
@Slf4j
@Component
@ConditionalOnProperty(name = "oauth.provider", havingValue = "mock", matchIfMissing = true)
public class MockOAuthClient implements OAuthClient {

    @Override
    public OAuthProfile fetchProfile(AuthProvider provider, String code, String state, String redirectUri) {
        String providerId = "mock-" + code;
        log.info("[MOCK OAUTH] provider={} providerId={}", provider, providerId);
        return new OAuthProfile(provider, providerId, "새벽테스터", null);
    }
}
