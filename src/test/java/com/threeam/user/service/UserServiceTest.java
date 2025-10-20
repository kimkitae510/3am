package com.threeam.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.dto.SignupRequest;
import com.threeam.user.dto.SignupResponse;
import com.threeam.user.entity.Role;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("회원가입 성공 - 비밀번호를 암호화해 저장하고 응답을 반환한다")
    void signup_success() {
        SignupRequest request = signupRequest("a@a.com", "password123", "닉네임");
        given(userRepository.existsByEmail("a@a.com")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("encodedPw");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        SignupResponse response = userService.signup(request);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("a@a.com");
        assertThat(response.getNickname()).isEqualTo("닉네임");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("encodedPw"); // 평문 저장 안 함
        assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("회원가입 실패 - 이미 존재하는 이메일이면 EMAIL_ALREADY_EXISTS 예외")
    void signup_duplicateEmail() {
        SignupRequest request = signupRequest("dup@a.com", "password123", "닉네임");
        given(userRepository.existsByEmail("dup@a.com")).willReturn(true);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    private SignupRequest signupRequest(String email, String password, String nickname) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        ReflectionTestUtils.setField(request, "nickname", nickname);
        return request;
    }
}
