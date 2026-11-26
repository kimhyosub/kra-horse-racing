package com.sub.gachima.service;

import com.sub.gachima.domain.Post;
import com.sub.gachima.telegram.TelegramClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.time.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 일간 총평: 결과가 다 나온 경주일 글을 만들고, 다시 돌리면 같은 글을 고친다. 운영자가 고친 글은 그대로 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:daily;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-daily",
        "gachima.auto-import=false"})
class DailyReviewTest {

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(ZonedDateTime.of(2026, 11, 16, 23, 10, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")); }
    }

    @Autowired ImportService importService;
    @Autowired DailyReviewService daily;
    @Autowired PostService posts;
    @MockBean TelegramClient tg;
    @MockBean ScreenshotReader reader;

    @Test
    void writesAndUpdates() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());

        Optional<DailyReviewService.Review> r = daily.build("20261115");
        assertTrue(r.isPresent());
        String body = r.get().body;
        assertTrue(r.get().title.startsWith("일간 총평 · 11월 15일(일) 서울 — 가치마 1위 "), r.get().title);
        assertTrue(body.contains("# 한 줄 요약"), body);
        assertTrue(body.contains("| 가치마 1위 우승 |"), body);
        assertTrue(body.contains("모델이 예상한 우승 수"), body);
        assertTrue(body.contains("# ★ 가치마"), body);
        assertTrue(body.contains("[검증 리포트](/stats?date=20261115)"), body);
        assertFalse(body.contains("영천 "), "영천 경주는 빼야 함");

        daily.nightly();
        Post p = posts.bySlug("daily-20261115").orElseThrow(AssertionError::new);
        assertEquals("daily", p.getCategory());
        assertTrue(p.isPublished());
        assertEquals(LocalDateTime.of(2026, 11, 15, 22, 0), p.getCreatedAt());

        // 다시 돌리면 새 글이 아니라 같은 글
        assertEquals(Boolean.FALSE, daily.save(r.get()));
        // 운영자가 고친 글은 건드리지 않는다
        posts.save(p.getId(), p.getSlug(), "고친 제목", p.getSummary(), p.getBody(), true, p.getSortOrder(), null, "daily");
        assertNull(daily.save(r.get()));
        assertEquals("고친 제목", posts.bySlug("daily-20261115").get().getTitle());
    }
}
