package com.threeam.mail;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mail.provider", havingValue = "smtp")
public class SmtpVerificationMailSender implements VerificationMailSender {

    private final JavaMailSender javaMailSender;
    private final MailProperties mailProperties;

    @Override
    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.getFrom());
        message.setTo(email);
        message.setSubject("[새벽 세시] 이메일 인증 코드");
        message.setText("인증 코드: " + code + "\n\n10분 안에 가입 화면에 입력해 주세요.\n"
                + "직접 요청하지 않았다면 이 메일은 무시하셔도 됩니다.");
        try {
            javaMailSender.send(message);
        } catch (Exception e) {
            // 수신 주소 오타, SMTP 장애 등. 코드 행은 트랜잭션 롤백으로 함께 사라진다.
            log.error("인증 메일 발송 실패 to={}", email, e);
            throw new BusinessException(ErrorCode.MAIL_SEND_FAILED);
        }
    }
}
