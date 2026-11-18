package com.sub.gachima.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 로그인. 사용자는 한 명 (환경변수 GACHIMA_LOGIN_USER / GACHIMA_LOGIN_PASSWORD).
 * 비밀번호를 정하지 않으면 실행할 때마다 임의 비밀번호를 만들어 콘솔에만 한 번 보여준다.
 * 휴대폰에서 매번 로그인하지 않도록 '로그인 유지'(30일).
 * gachima.login.public-read=true 면 경주·성적 화면은 로그인 없이 볼 수 있고, 입력·관리만 막는다.
 */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${gachima.login.user:admin}")
    private String user;
    @Value("${gachima.login.password:}")
    private String password;
    @Value("${gachima.login.public-read:false}")
    private boolean publicRead;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(PasswordEncoder enc) {
        if (password == null || password.trim().isEmpty()) {
            password = UUID.randomUUID().toString().substring(0, 12);
            log.warn("GACHIMA_LOGIN_PASSWORD 가 없어 이번 실행용 임시 비밀번호를 만들었습니다. 아이디 {} / 비밀번호 {}", user, password);
        }
        return new InMemoryUserDetailsManager(User.withUsername(user).password(enc.encode(password)).roles("ADMIN").build());
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http, UserDetailsService users) throws Exception {
        http.authorizeRequests(a -> {
                    a.antMatchers("/css/**", "/login", "/favicon.ico", "/error").permitAll();
                    if (publicRead) a.antMatchers(HttpMethod.GET, "/", "/r/**", "/stats").permitAll();
                    a.anyRequest().authenticated();
                })
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", false).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .rememberMe(r -> r.key(rememberKey()).tokenValiditySeconds(60 * 60 * 24 * 30).userDetailsService(users));
        return http.build();
    }

    /** 로그인 유지 쿠키 서명 키. 비밀번호가 바뀌면 기존 쿠키도 무효 */
    private String rememberKey() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("gachima:" + user + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
