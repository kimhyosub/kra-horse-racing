package com.sub.gachima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;
import com.sub.gachima.telegram.TelegramClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 기록 지문(결과 전 경주만 고정, 한 번 고정하면 그대로)과 3착 안 확률 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fp;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-fp",
        "gachima.auto-import=false"})
class FingerprintTest {

    @Autowired ImportService importService;
    @Autowired RaceService races;
    @Autowired Fingerprint fp;
    @Autowired ObjectMapper om;
    @MockBean TelegramClient tg;
    @MockBean ScreenshotReader reader;

    @Test
    void sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Fingerprint.sha256("abc"));
    }

    @Test
    void locksOnlyUnfinishedAndKeeps() throws Exception {
        JsonNode doc = om.readTree(new ClassPathResource("day-20261115.json").getInputStream());
        for (JsonNode m : doc.path("meets"))
            for (JsonNode r : m.path("races"))
                if (m.path("meet").asInt() == 1 && r.path("rcNo").asInt() == 7)
                    for (JsonNode h : r.path("horses")) ((ObjectNode) h).remove(Arrays.asList("ord", "finalOdds", "run"));
        File f = new File("target/test-fp/day-20261115.json");
        f.getParentFile().mkdirs();
        om.writeValue(f, doc);
        importService.importFile(f);

        assertEquals(1, fp.lock("20261115"));      // 결과가 없는 서울 7R만
        assertEquals(0, fp.lock("20261115"));      // 다시 해도 그대로

        RaceView v = races.view("20261115", 1, 7).get();
        assertNotNull(v.preHash);
        assertTrue(v.preHashText.startsWith("gachima-pre|20261115|1|7R|1:"), v.preHashText);
        assertEquals(Fingerprint.sha256(v.preHashText), v.preHash);
        assertTrue(v.preHashSame);
        assertEquals(12, v.getPreHashShort().length());
        assertNull(races.view("20261115", 1, 1).get().preHash);   // 결과가 있던 경주는 고정 안 함

        // 3착 안 확률: 합이 3, 1위 말이 가장 높다
        double s = 0;
        for (EntryView e : v.entries) { s += e.preTop3; assertTrue(e.preTop3 >= e.preP - 1e-9); }
        assertEquals(3.0, s, 1e-6);
        EntryView first = v.preOrder.get(0);
        for (EntryView e : v.entries) assertTrue(first.preTop3 >= e.preTop3 - 1e-9);
    }
}
