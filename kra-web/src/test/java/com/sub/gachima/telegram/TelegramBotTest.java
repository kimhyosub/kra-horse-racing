package com.sub.gachima.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.DayInput;
import com.sub.gachima.domain.Repositories.DayInputRepository;
import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.ScreenshotReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.time.*;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 텔레그램으로 스크린샷 → 경주 찾기 → 저장 → 1·2·3위·★ 답장 (텔레그램·Claude 는 가짜) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tg;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-tg",
        "gachima.auto-import=false",
        "gachima.telegram.chats=111,222",
        "gachima.public-url=https://gachima.example.com"})
class TelegramBotTest {

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(ZonedDateTime.of(2026, 11, 15, 13, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")); }
    }

    @Autowired TelegramBot bot;
    @Autowired ImportService importService;
    @Autowired DayInputRepository inputs;
    @Autowired ObjectMapper om;
    @MockBean TelegramClient tg;
    @MockBean ScreenshotReader reader;

    @BeforeEach
    void setUp() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());
        when(tg.isEnabled()).thenReturn(true);
        when(tg.download(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(reader.isEnabled()).thenReturn(true);
    }

    private void photo(long chat, String caption) throws Exception {
        bot.handle(om.readTree("{\"update_id\":1,\"message\":{\"message_id\":9,\"chat\":{\"id\":" + chat + "},"
                + "\"from\":{\"first_name\":\"Sub\"},\"caption\":\"" + caption + "\","
                + "\"photo\":[{\"file_id\":\"small\"},{\"file_id\":\"big\"}]}}"));
    }

    @Test
    void screenshotToSummary() throws Exception {
        ScreenshotReader.Reading r = new ScreenshotReader.Reading();
        r.raceNoSeen = 7; r.meetSeen = "서울"; r.track = "다습";
        double[] odds = {8.7, 13.8, 3.9, 10, 7, 13.1, 15.6, 15.4, 20.5, 4.1, 15.7};
        double[] bwd = {0, -1, 0, 1, 6, -1, 4, -7, 6, 2, 8};
        for (int i = 0; i < odds.length; i++) { r.odds.put(i + 1, odds[i]); r.bwd.put(i + 1, bwd[i]); }
        when(reader.read(anyList(), isNull(), eq(0), anyMap())).thenReturn(r);

        photo(111, "");

        verify(tg).download("big");
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tg).sendMessage(eq(111L), msg.capture(), eq(9L));
        String m = msg.getValue();
        assertTrue(m.contains("서울 7R"), m);
        assertTrue(m.contains("당일 분석"), m);
        assertTrue(m.contains("1위"), m);
        assertTrue(m.contains("8번 마스터플랜  15.4배"), m);      // ★
        assertTrue(m.contains("화면의 경주 번호로"), m);
        assertTrue(m.contains("결과가 나온 경주라 그대로"), m);
        assertTrue(m.contains("https://gachima.example.com/r/20261115/1/7?mode=day"), m);
        verify(tg).sendMessage(eq(222L), anyString(), isNull());     // 다른 대화에도 알림

        List<DayInput> saved = inputs.findAll().stream().filter(x -> "tg:Sub".equals(x.getCreatedBy())).collect(Collectors.toList());
        assertEquals(1, saved.size());
        assertEquals("다습", saved.get(0).getTrack());
    }

    @Test
    void captionWinsAndUnknownChatRefused() throws Exception {
        ScreenshotReader.Reading r = new ScreenshotReader.Reading();
        r.odds.put(1, 3.0); r.odds.put(2, 4.5); r.odds.put(3, 6.0);
        when(reader.read(anyList(), isNull(), eq(0), anyMap())).thenReturn(r);

        photo(999, "서울 3");
        verify(tg).sendMessage(eq(999L), contains("허용되지 않은 대화"), eq(9L));
        verify(reader, never()).read(anyList(), any(), anyInt(), anyMap());

        photo(111, "영천 3 양호");
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tg).sendMessage(eq(111L), msg.capture(), eq(9L));
        assertTrue(msg.getValue().contains("영천 3R"), msg.getValue());
        assertTrue(msg.getValue().contains("캡션대로"), msg.getValue());
        assertTrue(msg.getValue().contains("주로 양호"), msg.getValue());
        assertTrue(msg.getValue().contains("출전 취소로 계산"), msg.getValue());
    }

    @Test
    void idCommand() throws Exception {
        bot.handle(om.readTree("{\"update_id\":2,\"message\":{\"message_id\":5,\"chat\":{\"id\":333},\"text\":\"/id\"}}"));
        verify(tg).sendMessage(eq(333L), contains("<code>333</code>"), eq(5L));
    }

    @Test
    void parseCaption() {
        TelegramBot.Hint h = TelegramBot.parseCaption("부경 5R 다습");
        assertEquals(Integer.valueOf(3), h.meet);
        assertEquals(Integer.valueOf(5), h.rc);
        assertEquals("다습", h.track);
        assertNull(TelegramBot.parseCaption("").rc);

        LocalDate today = LocalDate.of(2026, 11, 19);
        TelegramBot.Hint d = TelegramBot.parseCaption("11/15 서울7", today);
        assertEquals(LocalDate.of(2026, 11, 15), d.date);
        assertEquals(Integer.valueOf(1), d.meet);
        assertEquals(Integer.valueOf(7), d.rc);
        TelegramBot.Hint k = TelegramBot.parseCaption("11월 15일 영천 3경주", today);
        assertEquals(LocalDate.of(2026, 11, 15), k.date);
        assertEquals(Integer.valueOf(3), k.rc);
        assertEquals(LocalDate.of(2025, 12, 28), TelegramBot.parseCaption("12.28 부경 1", LocalDate.of(2026, 1, 3)).date);
    }

    @Test
    void captionDateFindsPastRace() throws Exception {
        ScreenshotReader.Reading r = new ScreenshotReader.Reading();
        r.odds.put(8, 15.4); r.odds.put(3, 3.9); r.odds.put(10, 4.1);
        when(reader.read(anyList(), isNull(), eq(0), anyMap())).thenReturn(r);
        // 시계는 11/15 13:00 이지만, 캡션 날짜가 우선 — 없는 날짜면 안내
        photo(111, "11/16 서울7");
        verify(tg).sendMessage(eq(111L), contains("(11/16) 경주 데이터가 없습니다. 날짜를 확인해 주세요"), eq(9L));
    }
}
