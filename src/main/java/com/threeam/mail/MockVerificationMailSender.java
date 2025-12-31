package com.threeam.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 개발 스텁. 실제 발송 없이 로그로 코드를 확인한다. 실발송은 mail.provider=smtp.
@Slf4j
@Component
@ConditionalOnProperty(name = "mail.provider", havingValue = "mock", matchIfMissing = true)
public class MockVerificationMailSender implements VerificationMailSender {

    @Override
    public void send(String email, String code) {
        log.info("[MOCK MAIL] to={} 인증 코드={}", email, code);
    }
}
