package com.threeam.user.repository;

import com.threeam.user.entity.EmailVerification;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 재발송 시 이전 행을 지우므로 이메일당 행은 하나지만, 삭제 실패 등 만일에 대비해 최신 것을 쓴다.
    Optional<EmailVerification> findTopByEmailOrderByIdDesc(String email);

    void deleteByEmail(String email);
}
