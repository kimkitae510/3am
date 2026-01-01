package com.threeam.user.repository;

import com.threeam.user.entity.AuthProvider;
import com.threeam.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    // 가입 시 이메일 중복 검사는 탈퇴 계정까지 포함한다(이메일 재사용 차단).
    boolean existsByEmail(String email);

    // 소셜 로그인 식별. 탈퇴 계정도 조회해 "탈퇴한 계정" 안내와 재가입 차단에 쓴다.
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    // 로그인 등 살아있는 계정만 대상으로 하는 조회는 탈퇴자를 제외한다.
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);
}
