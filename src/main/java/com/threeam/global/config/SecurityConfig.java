package com.threeam.global.config;

import com.threeam.auth.oauth.OAuthProperties;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.mail.MailProperties;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.usage.UsageProperties;
import com.threeam.security.handler.JwtAccessDeniedHandler;
import com.threeam.security.handler.JwtAuthenticationEntryPoint;
import com.threeam.security.jwt.JwtAuthenticationFilter;
import com.threeam.security.jwt.JwtTokenProvider;
import com.threeam.security.jwt.TokenInvalidationRegistry;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, ChatPersonaProperties.class, UsageProperties.class,
        PaymentProperties.class, MailProperties.class, OAuthProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final TokenInvalidationRegistry tokenInvalidationRegistry;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 비동기(CompletableFuture) 응답의 ASYNC 재디스패치, 에러 포워딩은 최초 REQUEST에서
                        // 이미 인증을 통과한 내부 흐름 — Security 6은 기본으로 이것까지 검사해서,
                        // 면제하지 않으면 진단 API가 처리 완료 후 401로 떨어진다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/users/signup", "/api/users/email-verifications",
                                "/api/auth/login", "/api/auth/reissue", "/api/auth/oauth/**").permitAll()
                        // PG 웹훅 — 토스 서버가 호출하므로 JWT가 없다. 페이로드를 신뢰하지 않고
                        // PG 조회로 재확인하는 구조라(PaymentWebhookController) 열어도 상태 위조가 불가능하다.
                        .requestMatchers("/api/payments/webhook/**").permitAll()
                        // health만 공개(로드밸런서 헬스체크용). prometheus, info 등 내부 메트릭은
                        // 인증 뒤로 둔다 — 무인증 노출 시 트래픽 패턴, JVM, URI별 요청수가 새어 나간다.
                        // 모니터링 스크래핑은 인증 토큰 또는 내부망에서 호출한다.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, tokenInvalidationRegistry),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
