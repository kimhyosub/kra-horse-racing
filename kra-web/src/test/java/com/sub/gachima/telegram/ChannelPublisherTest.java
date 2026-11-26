package com.sub.gachima.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.ScreenshotReader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 공개 채널: 출발 전 입력 → 분석 글, 다시 입력 → 같은 글 고침, 결과 → 결과 글, 그 경마장 결과가 다 모이면 결산 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chan;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-chan",
        "gachima.auto-import=false",
        "gachima.telegram.channel=@gachima_test",
        "gachima.public-url=https://gachima.example.com"})
class ChannelPublisherTest {

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(ZonedDateTime.of(2026, 11, 15, 13, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")); }
    }

    @Autowired ImportService importService;
    @Autowired RaceService races;
    @Autowired ChannelPublisher channel;
    @Autowired ObjectMapper om;
    @MockBean TelegramClient tg;
    @MockBean ScreenshotReader reader;

    @Test
    void analysisEditResultSummary() throws Exception {
        when(tg.isEnabled()).thenReturn(true);
        when(tg.sendMessage(eq("@gachima_test"), anyString())).thenReturn(77L);

        // 서울 7R 만 결과를 지운 파일 (출발 전 상태)
        JsonNode doc = om.readTree(new ClassPathResource("day-20261115.json").getInputStream());
        for (JsonNode m : doc.path("meets"))
            for (JsonNode r : m.path("races"))
                if (m.path("meet").asInt() == 1 && r.path("rcNo").asInt() == 7)
                    for (JsonNode h : r.path("horses")) ((ObjectNode) h).remove(Arrays.asList("ord", "finalOdds"));
        File f = new File("target/test-chan/day-20261115.json");
        f.getParentFile().mkdirs();
        om.writeValue(f, doc);
        importService.importFile(f);
        assertTrue(channel.isEnabled());

        double[] odds = {8.7, 13.8, 3.9, 10, 7, 13.1, 15.6, 15.4, 20.5, 4.1, 15.7};
        double[] bwd = {0, -1, 0, 1, 6, -1, 4, -7, 6, 2, 8};
        Map<Integer, String> o = new HashMap<>(), w = new HashMap<>();
        for (int i = 0; i < odds.length; i++) { o.put(i + 1, String.valueOf(odds[i])); w.put(i + 1, String.valueOf(bwd[i])); }

        // 1) 분석 글
        races.saveInput("20261115", 1, 7, "다습", o, w, "sub");
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tg, timeout(3000)).sendMessage(eq("@gachima_test"), msg.capture());
        String a = msg.getValue();
        assertTrue(a.contains("서울 7R"), a);
        assertTrue(a.contains("14:50 출발"), a);
        assertTrue(a.contains("1위 "), a);
        assertTrue(a.contains("★ <b>8번 마스터플랜</b>  단승 15.4배"), a);
        assertTrue(a.contains("13:00 배당 기준"), a);
        assertTrue(a.contains("🔒 ★ 기록 지문 <code>"), a);
        assertTrue(a.contains("https://gachima.example.com/r/20261115/1/7?mode=day"), a);

        // 2) 다시 입력 → 새 글이 아니라 같은 글(77) 고침
        races.saveInput("20261115", 1, 7, "다습", o, w, "sub");
        verify(tg, timeout(3000)).editMessage(eq("@gachima_test"), eq(77L), anyString());

        // 3) 결과 → 결과 글 + (서울 다른 경주는 이미 결과가 있으므로) 하루 결산
        Map<Integer, Integer> ords = new LinkedHashMap<>();
        ords.put(8, 1); ords.put(3, 2); ords.put(10, 3);
        Map<Integer, Double> fin = new HashMap<>();
        fin.put(8, 16.2);
        races.saveResult("20261115", 1, 7, ords, Collections.<Integer>emptySet(), fin);
        verify(tg, timeout(3000).times(3)).sendMessage(eq("@gachima_test"), msg.capture());
        List<String> all = msg.getAllValues();
        String res = all.get(all.size() - 2), sum = all.get(all.size() - 1);
        assertTrue(res.contains("서울 7R 결과"), res);
        assertTrue(res.contains("1착 8번 마스터플랜 (16.2배)"), res);
        assertTrue(res.contains("<b>적중</b> 16.2배 (+15,200원)"), res);
        assertTrue(sum.contains("11/15(일) 가치마 결산"), sum);
        assertTrue(sum.contains("분석 1경주"), sum);
        assertTrue(sum.contains("1건 중 1건 적중 · 1,000원씩 +15,200원"), sum);
        assertTrue(sum.contains("/stats?date=20261115"), sum);

        // 같은 결과가 다시 들어와도(공식 결과 수집) 두 번 올리지 않는다
        races.saveResult("20261115", 1, 7, ords, Collections.<Integer>emptySet(), fin);
        Thread.sleep(300);
        verify(tg, times(3)).sendMessage(eq("@gachima_test"), anyString());
    }

    @Test
    void formatsWithoutStar() {
        assertEquals("+15,200원", ChannelPublisher.signedWon(15200));
        assertEquals("-3,000원", ChannelPublisher.signedWon(-3000));
        assertEquals("31%", ChannelPublisher.pct(0.314));
    }
}
