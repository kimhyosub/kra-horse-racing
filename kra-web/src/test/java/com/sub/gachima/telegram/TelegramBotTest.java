package com.sub.gachima.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.DayInput;
import com.sub.gachima.domain.Repositories.DayInputRepository;
import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    @Autowired RaceService raceService;
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
    void trackByText() throws Exception {
        assertEquals("다습", TelegramBot.trackOf("함수율 12"));
        assertEquals("양호", TelegramBot.trackOf("부경 8%"));
        assertEquals("포화", TelegramBot.trackOf("제주 포화"));
        assertNull(TelegramBot.trackOf("내일 몇 시?"));
        TelegramBot.Hint h = TelegramBot.parseCaption("함수율 12 서울 7", LocalDate.of(2026, 11, 15));
        assertEquals("다습", h.track);
        assertEquals(Integer.valueOf(7), h.rc);

        bot.handle(om.readTree("{\"update_id\":3,\"message\":{\"message_id\":4,\"chat\":{\"id\":111},\"text\":\"영천 함수율 17\"}}"));
        verify(tg).sendMessage(eq(111L), contains("영천 주로를 <b>포화</b>"), eq(4L));

        ScreenshotReader.Reading r = new ScreenshotReader.Reading();
        r.odds.put(1, 3.0); r.odds.put(2, 4.5); r.odds.put(3, 6.0);
        when(reader.read(anyList(), isNull(), eq(0), anyMap())).thenReturn(r);
        photo(111, "영천 3");
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tg).sendMessage(eq(111L), msg.capture(), eq(9L));
        assertTrue(msg.getValue().contains("주로 포화"), msg.getValue());
        assertFalse(msg.getValue().contains("주로 상태를 못 읽어"), msg.getValue());

        // 잡담은 무시
        bot.handle(om.readTree("{\"update_id\":4,\"message\":{\"message_id\":5,\"chat\":{\"id\":111},\"text\":\"오늘 저녁 뭐 먹지\"}}"));
        verify(tg, never()).sendMessage(eq(111L), anyString(), eq(5L));
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

    // ---------------- 결과 ----------------

    private void text(long chat, String t) throws Exception {
        bot.handle(om.readTree("{\"update_id\":5,\"message\":{\"message_id\":7,\"chat\":{\"id\":" + chat + "},"
                + "\"from\":{\"first_name\":\"Sub\"},\"text\":\"" + t + "\"}}"));
    }

    private Integer ord(int meet, int rc, int no) {
        RaceView v = raceService.view("20261115", meet, rc).get();
        for (EntryView e : v.entries) if (e.no == no) return e.ord;
        throw new AssertionError(no);
    }

    @Test
    void parseResultText() {
        LocalDate d = LocalDate.of(2026, 11, 15);
        TelegramBot.ResultText a = TelegramBot.parseResult("서울 7 결과 5-2-7", d);
        assertEquals(Integer.valueOf(1), a.hint.meet);
        assertEquals(Integer.valueOf(7), a.hint.rc);
        assertNull(a.hint.date);                                   // 5-2 를 날짜로 읽지 않음
        assertEquals(java.util.Arrays.asList(5, 2, 7), a.order);

        TelegramBot.ResultText b = TelegramBot.parseResult("결과 부경3R 4 1 9 취소 6, 8 단승 12.5", d);
        assertEquals(Integer.valueOf(3), b.hint.meet);
        assertEquals(Integer.valueOf(3), b.hint.rc);
        assertEquals(java.util.Arrays.asList(4, 1, 9), b.order);
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(6, 8)), b.scratched);
        assertEquals(Double.valueOf(12.5), b.winOdds);

        TelegramBot.ResultText c = TelegramBot.parseResult("11/14 제주 2경주 착순 1착 5 2착 3 3착 8", d);
        assertEquals(LocalDate.of(2026, 11, 14), c.hint.date);
        assertEquals(Integer.valueOf(2), c.hint.rc);
        assertEquals(java.util.Arrays.asList(5, 3, 8), c.order);

        assertTrue(TelegramBot.parseResult("결과 7", d).order.isEmpty());
    }

    @Test
    void resultByText() throws Exception {
        text(111, "서울 7 결과 5-2-7 단승 9.1");
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tg).sendMessage(eq(111L), msg.capture(), eq(7L));
        String m = msg.getValue();
        assertTrue(m.contains("서울 7R 결과"), m);
        assertTrue(m.contains("1착  5번"), m);
        assertTrue(m.contains("단승 9.1배"), m);
        assertTrue(m.contains("전날 1위"), m);
        assertTrue(m.contains("★ 모의 기록"), m);
        assertTrue(m.contains("공식 결과가 수집되면"), m);
        verify(tg).sendMessage(eq(222L), contains("서울 7R 결과"), isNull());
        assertEquals(Integer.valueOf(1), ord(1, 7, 5));
        assertEquals(Integer.valueOf(3), ord(1, 7, 7));
        assertEquals(Integer.valueOf(com.sub.gachima.domain.Entry.ORD_OUT), ord(1, 7, 3));

        // 착순이 아직 없는 파일을 다시 불러와도 직접 넣은 결과는 그대로
        ObjectNode doc = (ObjectNode) om.readTree(new ClassPathResource("day-20261115.json").getFile());
        for (JsonNode mt : doc.path("meets")) for (JsonNode r : mt.path("races")) for (JsonNode h : r.path("horses")) {
            ((ObjectNode) h).remove("ord"); ((ObjectNode) h).remove("finalOdds");
        }
        java.io.File f = new java.io.File("target/test-import-tg/day-20261115-noord.json");
        f.getParentFile().mkdirs();
        om.writeValue(f, doc);
        importService.importFile(f);
        assertEquals(Integer.valueOf(1), ord(1, 7, 5));

        // 공식 결과(착순 있는 파일)가 오면 덮어씀
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());
        assertEquals(Integer.valueOf(1), ord(1, 7, 3));
    }

    @Test
    void resultTextAsksWhenUnclear() throws Exception {
        text(111, "3 결과 1-2-3");      // 서울·영천 모두 3경주
        verify(tg).sendMessage(eq(111L), contains("경마장을 적어 주세요"), eq(7L));
        text(222, "서울 7 결과 4-4-1");
        verify(tg).sendMessage(eq(222L), contains("4번이 두 번"), eq(7L));
        text(111, "오늘 결과 좋네");      // 숫자 없는 잡담은 무시
        verify(tg, times(1)).sendMessage(eq(111L), anyString(), eq(7L));
        text(111, "서울 7 결과 30-2-1");
        verify(tg).sendMessage(eq(111L), contains("출전마가 아닙니다"), eq(7L));
    }

    @Test
    void resultByScreenshot() throws Exception {
        ScreenshotReader.Reading r = new ScreenshotReader.Reading();
        r.result = true; r.raceNoSeen = 4; r.meetSeen = "영천";
        r.ord.put(2, 1); r.ord.put(6, 2); r.ord.put(1, 3);
        r.odds.put(2, 7.7);
        when(reader.read(anyList(), isNull(), eq(0), anyMap())).thenReturn(r);
        photo(111, "");
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tg).sendMessage(eq(111L), msg.capture(), eq(9L));
        String m = msg.getValue();
        assertTrue(m.contains("영천 4R 결과"), m);
        assertTrue(m.contains("1착  2번"), m);
        assertTrue(m.contains("단승 7.7배"), m);
        RaceView v = raceService.view("20261115", 4, 4).get();
        for (EntryView e : v.entries) if (e.no == 2) assertEquals(Integer.valueOf(1), e.ord);
    }
}
