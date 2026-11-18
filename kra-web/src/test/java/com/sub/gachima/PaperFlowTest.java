package com.sub.gachima;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sub.gachima.domain.PaperBet;
import com.sub.gachima.domain.Repositories.PaperBetRepository;
import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.PaperService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.File;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 모의 기록: 출발 전 입력 → ★ 기록(마지막 입력이 공식) → 결과 불러오기 → 정산 / 예전 기록 옮기기 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paper;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-paper",
        "gachima.auto-import=false"})
@AutoConfigureMockMvc
class PaperFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;
    @Autowired PaperService paperService;
    @Autowired PaperBetRepository bets;
    @Autowired ObjectMapper om;

    static final String FUTURE = "20991231";

    /** 11/15 서울 데이터를 먼 미래 날짜로 바꾼 파일. withResult=false 면 착순·확정 배당을 지운다 */
    private File futureDay(boolean withResult) throws Exception {
        ObjectNode doc = (ObjectNode) om.readTree(new ClassPathResource("day-20261115.json").getFile());
        doc.put("date", FUTURE);
        for (JsonNode m : doc.path("meets"))
            for (JsonNode r : m.path("races")) {
                if (!withResult) ((ObjectNode) r).remove("final");
                for (JsonNode h : r.path("horses"))
                    if (!withResult) { ((ObjectNode) h).putNull("ord"); ((ObjectNode) h).putNull("finalOdds"); }
            }
        File f = new File("target/paper-day-" + withResult + ".json");
        om.writeValue(f, doc);
        return f;
    }

    private MockHttpServletRequestBuilder input7(String odds8) {
        return post("/r/" + FUTURE + "/1/7/input")
                .param("track", "다습")
                .param("odds_1", "8.7").param("bwd_1", "0")
                .param("odds_2", "13.8").param("bwd_2", "-1")
                .param("odds_3", "3.9").param("bwd_3", "0")
                .param("odds_4", "10").param("bwd_4", "+1")
                .param("odds_5", "7").param("bwd_5", "6")
                .param("odds_6", "13.1").param("bwd_6", "-1")
                .param("odds_7", "15.6").param("bwd_7", "4")
                .param("odds_8", odds8).param("bwd_8", "-7")
                .param("odds_9", "20.5").param("bwd_9", "6")
                .param("odds_10", "4.1").param("bwd_10", "2")
                .param("odds_11", "15.7").param("bwd_11", "8");
    }

    @Test
    void recordLastInputThenSettle() throws Exception {
        importService.importFile(futureDay(false));

        // 첫 입력: 8번이 ★ (15.4배)
        mvc.perform(input7("15.4")).andExpect(flash().attribute("paperMsg", containsString("바꿨습니다")));
        List<PaperBet> b = bets.findByRaceDateAndMeetAndRcNoOrderByChulNo(FUTURE, 1, 7);
        assertEquals(1, b.size());
        assertEquals(8, b.get(0).getChulNo());
        assertEquals(15.4, b.get(0).getBetOdds(), 1e-9);

        // 다시 입력: 8번 배당이 3.0 으로 내려가면 ★ 가 아니다 → 기록에서 빠진다 (마지막 입력이 공식)
        mvc.perform(input7("3.0"));
        assertTrue(bets.findByRaceDateAndMeetAndRcNoOrderByChulNo(FUTURE, 1, 7).stream().noneMatch(x -> x.getChulNo() == 8));

        // 마지막으로 다시 15.4
        mvc.perform(input7("15.4"));
        mvc.perform(get("/r/" + FUTURE + "/1/7").param("mode", "day"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("★ 8번 마스터플랜 15.4배")));

        // 결과가 들어오면 정산 (11/15 실제 결과: 3-2-1, 8번 마스터플랜 미적중)
        importService.importFile(futureDay(true));
        PaperBet s = bets.findByRaceDateAndMeetAndRcNoAndChulNo(FUTURE, 1, 7, 8).orElseThrow(AssertionError::new);
        assertTrue(s.isSettled());
        assertNotNull(s.getFinishOrd());
        assertFalse(s.isWin());
        assertNotNull(s.getFinalOdds());

        // 결과가 나온 뒤 입력은 기록을 바꾸지 않는다
        mvc.perform(input7("3.0")).andExpect(flash().attribute("paperMsg", containsString("결과가 나온")));
        assertTrue(bets.findByRaceDateAndMeetAndRcNoAndChulNo(FUTURE, 1, 7, 8).isPresent());

        mvc.perform(get("/stats")).andExpect(status().isOk()).andExpect(content().string(containsString("모의 장부")));

        // 다른 테스트(옮긴 기록 합계)에 섞이지 않게 정리
        bets.deleteAll(bets.findByRaceDateAndMeetAndRcNoOrderByChulNo(FUTURE, 1, 7));
    }

    @Test
    void migratedLedger() throws Exception {
        assertEquals(32, paperService.importFile(new ClassPathResource("paper-bets.json").getFile()));
        // 다시 불러와도 늘지 않는다
        paperService.importFile(new ClassPathResource("paper-bets.json").getFile());
        long migrated = bets.findAll().stream().filter(x -> PaperService.SRC_MIGRATED.equals(x.getSource())).count();
        assertEquals(32, migrated);

        PaperService.Row total = paperService.ledger().rows.stream().filter(r -> r.total).findFirst().orElseThrow(AssertionError::new);
        assertEquals(28, total.n);       // 영천 4건 제외
        assertEquals(2, total.wins);     // 블레이징엔진 10.9, 큐피드천궁 2.1
        assertEquals(13.0, total.ret, 1e-9);

        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("블레이징엔진")))
                .andExpect(content().string(containsString("46%")));
    }
}
