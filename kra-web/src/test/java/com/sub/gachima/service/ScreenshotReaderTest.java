package com.sub.gachima.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Claude 응답 해석: 도구 호출로 오든, 글 속 JSON 으로 오든 같은 값 */
class ScreenshotReaderTest {

    final ObjectMapper om = new ObjectMapper();
    final ScreenshotReader reader = new ScreenshotReader(om, "k", "m", "u");

    @Test
    void toolUse() throws Exception {
        ScreenshotReader.Reading r = reader.parse(om.readTree("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"...\"},"
                + "{\"type\":\"tool_use\",\"name\":\"record_race_screen\",\"input\":{\"race_no_seen\":7,\"track\":\"다습\","
                + "\"horses\":[{\"no\":1,\"odds\":8.7,\"bwd\":0},{\"no\":8,\"odds\":15.4,\"bwd\":-7},{\"no\":5,\"scratched\":true},{\"no\":3,\"odds\":0.5}]}}]}"));
        assertEquals(Integer.valueOf(7), r.raceNoSeen);
        assertEquals("다습", r.track);
        assertEquals(15.4, r.odds.get(8), 1e-9);
        assertEquals(-7.0, r.bwd.get(8), 1e-9);
        assertFalse(r.odds.containsKey(5));   // 취소
        assertFalse(r.odds.containsKey(3));   // 1.0 미만은 버림
    }

    @Test
    void jsonInText() throws Exception {
        ScreenshotReader.Reading r = reader.parse(om.readTree("{\"content\":[{\"type\":\"text\",\"text\":"
                + "\"읽은 값입니다.\\n```json\\n{\\\"track\\\":\\\"양호\\\",\\\"horses\\\":[{\\\"no\\\":2,\\\"odds\\\":3.1}]}\\n```\"}]}"));
        assertEquals("양호", r.track);
        assertEquals(3.1, r.odds.get(2), 1e-9);
    }

    @Test
    void nothingRead() {
        assertThrows(java.io.IOException.class, () -> reader.parse(om.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"모르겠습니다\"}]}")));
    }

    @Test
    void requestHasNoForcedTool() throws Exception {
        String req = om.writeValueAsString(reader.request(
                Collections.singletonList(new ScreenshotReader.Image(new byte[]{1, 2}, "image/png")), "서울", 7, new TreeMap<>()));
        assertTrue(req.contains("\"tool_choice\":{\"type\":\"auto\"}"));
        assertTrue(req.contains("\"media_type\":\"image/png\""));
    }
}
