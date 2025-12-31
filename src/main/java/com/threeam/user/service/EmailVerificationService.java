package com.threeam.user.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.mail.VerificationMailSender;
import com.threeam.user.entity.EmailVerification;
import com.threeam.user.repository.EmailVerificationRepository;
import com.threeam.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;

    private final EmailVerificationRepository emailVerificationRepository;
    private final UserRepository userRepository;
    private final VerificationMailSender mailSender;
    private final MailSendRateLimiter mailSendRateLimiter;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public void issue(String email, String clientIp) {
        // 이미 가입된 이메일이면 메일을 보내기 전에 알려준다. 존재 여부가 노출되지만,
        // 어차피 가입 API가 같은 정보를 돌려주므로(EMAIL_ALREADY_EXISTS) 새는 정보가 아니다.
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        mailSendRateLimiter.check(clientIp);

        LocalDateTime now = LocalDateTime.now();
        emailVerificationRepository.findTopByEmailOrderByIdDesc(email)
                .filter(prev -> prev.getCreatedAt() != null
                        && prev.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now))
                .ifPresent(prev -> {
                    throw new BusinessException(ErrorCode.VERIFICATION_RESEND_COOLDOWN);
                });

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        emailVerificationRepository.deleteByEmail(email);
        emailVerificationRepository.save(EmailVerification.builder()
                .email(email)
                .codeHash(hash(code))
                .expiresAt(now.plus(CODE_TTL))
                .build());

        // 발송 실패 시 예외로 트랜잭션이 롤백되어 코드 행도 남지 않는다.
        mailSender.send(email, code);
    }

    // 가입 트랜잭션과 분리(REQUIRES_NEW): 실패 코드 입력 시 시도 횟수 증가가
    // 바깥 예외에 딸려 롤백되면 무한 재시도가 가능해진다. noRollbackFor로 증가분을 커밋한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public void verifyAndConsume(String email, String code) {
        EmailVerification verification = emailVerificationRepository.findTopByEmailOrderByIdDesc(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID));

        if (verification.isExpired(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (verification.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!verification.matches(hash(code))) {
            verification.increaseAttempt();
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        }

        // 성공 즉시 소비(삭제) — 같은 코드로 두 번 가입 시도할 수 없다.
        emailVerificationRepository.deleteByEmail(email);
    }

    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
