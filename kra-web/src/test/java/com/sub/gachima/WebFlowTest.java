package com.sub.gachima;

import com.sub.gachima.service.ImportScanner;
import com.sub.gachima.service.ImportService;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 불러오기 → 전날 예상 화면 → 당일 입력 → 당일 분석 화면 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gachima;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import",
        "gachima.auto-import=false"})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class WebFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;
    @Autowired ImportScanner scanner;

    @Test
    void preThenDay() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());

        // 첫 화면: 가장 가까운 경주일(여기선 지난 15일) 요약과 경주별 가치마 1위
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("2026년 11월 15일 (일)")))
                .andExpect(content().string(containsString("오늘 경주를 이렇게 봅니다")))
                .andExpect(content().string(containsString("가치마 1위 확률이 30%를 넘는 경주는")))
                .andExpect(content().string(containsString("href=\"/r/20261115/1/7")))
                .andExpect(content().string(containsString("class=\"hm-row")));

        mvc.perform(get("/r/20261115/1/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("전날 예상 5두")))
                .andExpect(content().string(containsString("전날 예상 읽는 법")))
                .andExpect(content().string(containsString("포인트아이")))
                .andExpect(content().string(containsString("7전 2승 · 입상 4 · 최근 1·4·1·2·4착 · 28일 만")))
                .andExpect(content().string(containsString("최근 성적 좋음")))
                .andExpect(content().string(containsString("마스터플랜")));

        mvc.perform(post("/r/20261115/1/7/input").with(csrf())
                        .param("track", "다습")
                        .param("odds_1", "8.7").param("bwd_1", "0")
                        .param("odds_2", "13.8").param("bwd_2", "-1")
                        .param("odds_3", "3.9").param("bwd_3", "0")
                        .param("odds_4", "10").param("bwd_4", "+1")
                        .param("odds_5", "7").param("bwd_5", "6")
                        .param("odds_6", "13.1").param("bwd_6", "-1")
                        .param("odds_7", "15.6").param("bwd_7", "4")
                        .param("odds_8", "15.4").param("bwd_8", "-7")
                        .param("odds_9", "20.5").param("bwd_9", "6")
                        .param("odds_10", "4.1").param("bwd_10", "2")
                        .param("odds_11", "15.7").param("bwd_11", "8"))
                .andExpect(redirectedUrl("/r/20261115/1/7?mode=day"));

        mvc.perform(get("/r/20261115/1/7").param("mode", "day"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("★ 8번 15.4배")))
                .andExpect(content().string(containsString("1.31")))
                .andExpect(content().string(containsString("당일 분석 읽는 법")))
                .andExpect(content().string(containsString("가치마 총평")))
                .andExpect(content().string(containsString("순서까지 맞을 확률")))
                .andExpect(content().string(containsString("승식별 추천")))
                .andExpect(content().string(containsString("이 경주는 단승")))
                .andExpect(content().string(containsString("삼쌍승")))
                .andExpect(content().string(containsString("배당이 보는 확률")))
                .andExpect(content().string(containsString("당일 1·2·3위")))
                .andExpect(content().string(containsString("→ 1.10 이상, ★ 가치마")));

        // 점수표
        mvc.perform(get("/r/20261115/1/7/score"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("점수표 읽는 법")))
                .andExpect(content().string(containsString("class=\"picker\"")))                 // 다른 경주 화면과 같은 머리
                .andExpect(content().string(containsString("href=\"/r/20261115/1/6/score\"")))   // 경주 번호를 누르면 그 경주 점수표
                .andExpect(content().string(containsString("확정 착순")))
                .andExpect(content().string(containsString("최근 성적")))
                .andExpect(content().string(containsString("1·4·1착")))
                .andExpect(content().string(containsString("기대값")));

        // 가중치
        mvc.perform(get("/model").param("date", "20261115").param("meet", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("전날 예상 모델")))
                .andExpect(content().string(containsString("배당 반영")))
                .andExpect(content().string(containsString("나이가 어릴수록 유리")));

        mvc.perform(get("/r/20261115/4/3")).andExpect(status().isOk());
        mvc.perform(get("/r/20261115/1/99")).andExpect(status().isNotFound());

        // 직접 입력이 없는 경주는 확정 최종 배당으로 계산된다
        mvc.perform(get("/r/20261115/1/1").param("mode", "day"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("확정 최종 배당")));

        // 성적: 서울 11경주 중 전날 1위 적중 4 (export 기준 Python 계산값)
        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("36.4% (4)")))
                .andExpect(content().string(containsString("당일 분석 1위")))
                .andExpect(content().string(containsString("승식별 회수율")))
                .andExpect(content().string(containsString("이날 당일 분석은 얼마나 맞았나")))
                .andExpect(content().string(containsString("11월 15일 (일)")))
                .andExpect(content().string(containsString("최근 1경주일 누적")))
                .andExpect(content().string(containsString("<polyline")))
                .andExpect(content().string(containsString("경주별 예상 · 결과 비교")))
                .andExpect(content().string(containsString("href=\"/r/20261115/1/7?mode=day\"")))
                .andExpect(content().string(containsString("5두 안 3착")))
                .andExpect(content().string(containsString("같은 경주 인기 1위 우승")))
                .andExpect(content().string(containsString("확정 배당 기준")))
                .andExpect(content().string(containsString("이날 당일 상위 5두를 박스로 샀다면")))
                .andExpect(content().string(containsString("복승 10조합")))
                .andExpect(content().string(containsString("복연 10조합")))
                .andExpect(content().string(containsString("href=\"/stats?date=20261115&amp;box=3\"")))
                .andExpect(content().string(containsString("BY BET TYPE")))
                .andExpect(content().string(containsString("1위→2위→3위")))
                .andExpect(content().string(containsString("10.7% / 80%")))        // 삼복승 과거 검증
                .andExpect(content().string(containsString("1경주 · 적중")))        // 서울 7R 만 승식 배당이 있음
                .andExpect(content().string(containsString("가치마 1위 vs 배당 1위")))
                .andExpect(content().string(containsString("가치마 확률은 맞나")))
                .andExpect(content().string(containsString("19세 미만은 마권을 구매할 수 없습니다")))
                .andExpect(content().string(containsString("데이터 출처: 한국마사회 공개 자료")))
                .andExpect(content().string(containsString("class=\"tabbar\"")))
                .andExpect(content().string(containsString("공식 합계")));
        mvc.perform(get("/stats").param("date", "20261115").param("box", "3")).andExpect(status().isOk())
                .andExpect(content().string(containsString("이날 당일 상위 3두를 박스로 샀다면")))
                .andExpect(content().string(containsString("쌍승 6조합")))
                .andExpect(content().string(containsString("복승 81%")));
        mvc.perform(get("/stats").param("date", "20990101")).andExpect(status().isOk())        // 없는 날은 최근 날로
                .andExpect(content().string(containsString("11월 15일 (일)")));
        mvc.perform(get("/admin")).andExpect(status().isOk());
    }

    @Test
    void scannerSkipsUnchangedFiles() throws Exception {
        java.io.File dir = importService.getDir();
        dir.mkdirs();
        java.nio.file.Files.copy(new ClassPathResource("day-20261115.json").getFile().toPath(),
                new java.io.File(dir, "day-20261115.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ImportScanner.Result first = scanner.scan(false);
        org.junit.jupiter.api.Assertions.assertEquals(1, first.ok.size(), first.message());
        ImportScanner.Result second = scanner.scan(false);
        org.junit.jupiter.api.Assertions.assertEquals(0, second.ok.size());
        org.junit.jupiter.api.Assertions.assertEquals(1, second.skipped);
        org.junit.jupiter.api.Assertions.assertEquals(1, scanner.scan(true).ok.size());

        // 자동 수집 스크립트가 남긴 상태가 관리 화면에 보인다
        java.nio.file.Files.write(new java.io.File(dir, "collect-status.json").toPath(),
                ("{\"startedAt\":\"2026-11-19 21:40:00\",\"finishedAt\":\"2026-11-19 21:52:10\",\"ok\":true,"
                 + "\"from\":\"2026-11-18\",\"to\":\"2026-11-20\",\"message\":\"완료\",\"log\":\"C:\\\\kra-data\\\\logs\\\\x.log\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026-11-19 21:52:10 끝")));
    }
}
