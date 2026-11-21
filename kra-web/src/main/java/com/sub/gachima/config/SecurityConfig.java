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
import java.io.*;
import java.util.*;

/**
 * 로그인.
 *  - 주인(ADMIN): 환경변수 GACHIMA_LOGIN_USER / GACHIMA_LOGIN_PASSWORD. 모든 화면 + 관리
 *  - 추가 사용자(INPUT): 사용자 파일(기본 C:/kra-data/gachima-users.properties)의 "아이디 = 비밀번호" 줄.
 *    경주·성적 보기, 당일 배당 입력, 스크린샷 가능. 관리 화면은 못 들어감. 파일을 고치면 웹을 다시 실행.
 * 비밀번호를 정하지 않으면 실행할 때마다 임의 비밀번호를 만들어 콘솔에만 한 번 보여준다.
 * 휴대폰에서 매번 로그인하지 않도록 '로그인 유지'(30일).
 * gachima.login.public-read=true(기본, 공개 베타)면 경주·점수표·성적·가중치·이용안내는 로그인 없이 볼 수 있고, 입력·관리만 막는다.
 */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${gachima.login.user:admin}")
    private String user;
    @Value("${gachima.login.password:}")
    private String password;
    @Value("${gachima.login.public-read:true}")
    private boolean publicRead;
    @Value("${gachima.login.users-file:C:/kra-data/gachima-users.properties}")
    private String usersFile;

    public static final String ADMIN = "ADMIN", INPUT = "INPUT";

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
        InMemoryUserDetailsManager m = new InMemoryUserDetailsManager(User.withUsername(user).password(enc.encode(password)).roles(ADMIN).build());
        for (Map.Entry<String, String> e : readUsersFile(new File(usersFile)).entrySet()) {
            if (e.getKey().equals(user)) { log.warn("사용자 파일의 {} 는 주인 아이디와 같아서 건너뜀", e.getKey()); continue; }
            String pw = e.getValue();
            m.createUser(User.withUsername(e.getKey()).password(pw.startsWith("{") ? pw : enc.encode(pw)).roles(INPUT).build());
        }
        return m;
    }

    /** "아이디 = 비밀번호" (UTF-8, # 주석). 비밀번호 자리에 {bcrypt}... 해시를 써도 된다 */
    static Map<String, String> readUsersFile(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.isFile()) return out;
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            log.error("사용자 파일을 읽지 못했습니다: {}", f.getAbsolutePath(), e);
            return out;
        }
        for (String id : new TreeSet<>(p.stringPropertyNames())) {
            String pw = p.getProperty(id).trim(), name = id.trim();
            if (name.isEmpty() || pw.length() < 4) { log.warn("사용자 파일: '{}' 는 비밀번호가 너무 짧아 건너뜀 (4자 이상)", name); continue; }
            out.put(name, pw);
        }
        log.info("추가 사용자 {}명 (입력 가능, 관리 불가): {}", out.size(), out.keySet());
        return out;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http, UserDetailsService users) throws Exception {
        http.authorizeRequests(a -> {
                    a.antMatchers("/css/**", "/login", "/favicon.ico", "/favicon.svg", "/logo.svg", "/manifest.json", "/icons/**", "/error", "/robots.txt", "/sitemap.xml", "/ads.txt", "/og.png").permitAll();
                    a.antMatchers("/admin/**", "/admin").hasRole(ADMIN);
                    a.antMatchers(HttpMethod.POST, "/r/**").hasAnyRole(ADMIN, INPUT);
                    a.antMatchers(HttpMethod.GET, "/about", "/guide", "/guide/**").permitAll();
                    if (publicRead) a.antMatchers(HttpMethod.GET, "/", "/r/**", "/stats", "/model").permitAll();
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
