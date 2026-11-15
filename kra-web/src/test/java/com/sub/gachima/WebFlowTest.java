package com.sub.gachima;

import com.sub.gachima.service.ImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 불러오기 → 전날 예상 화면 → 당일 입력 → 당일 분석 화면 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gachima;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import"})
@AutoConfigureMockMvc
class WebFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;

    @Test
    void preThenDay() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());

        mvc.perform(get("/")).andExpect(redirectedUrl("/r/20261115/1/1"));

        mvc.perform(get("/r/20261115/1/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("전날 예상 5두")))
                .andExpect(content().string(containsString("마스터플랜")));

        mvc.perform(post("/r/20261115/1/7/input")
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
                .andExpect(content().string(containsString("1.31")));

        mvc.perform(get("/r/20261115/4/3")).andExpect(status().isOk());
        mvc.perform(get("/r/20261115/1/99")).andExpect(status().isNotFound());
    }
}
