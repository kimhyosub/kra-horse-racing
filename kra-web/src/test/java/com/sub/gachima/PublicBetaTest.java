package com.sub.gachima;

import com.sub.gachima.service.ImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 공개 베타(기본): 보기는 로그인 없이, 입력·관리는 로그인. 방문자에게는 입력자·입력 버튼이 안 보인다 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:public;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-public",
        "gachima.auto-import=false",
        "gachima.login.user=sub",
        "gachima.login.password=test-pass-123"})
@AutoConfigureMockMvc
class PublicBetaTest {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;

    @BeforeEach
    void load() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());
    }

    @Test
    void visitorsCanReadOnly() throws Exception {
        for (String u : new String[]{"/r/20261115/1/7", "/r/20261115/1/7/score", "/stats", "/model", "/about"})
            mvc.perform(get(u)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("1336")))          // 바닥글 고지
                    .andExpect(content().string(containsString("관리자 로그인")))
                    .andExpect(content().string(not(containsString("로그아웃"))));
        mvc.perform(get("/robots.txt")).andExpect(status().isOk()).andExpect(content().string(containsString("Disallow: /admin")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk());
        mvc.perform(get("/ads.txt")).andExpect(status().isNotFound());   // 애드센스 ID 없으면 없음
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/r/20261115/1/7/input").with(csrf()).param("odds_1", "3.0").param("odds_2", "4.0"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/about")).andExpect(content().string(containsString("참고용 분석")));
    }

    @Test
    void visitorsDoNotSeeWhoEntered() throws Exception {
        mvc.perform(post("/r/20261115/1/7/input").with(csrf()).with(user("운영자").roles("ADMIN"))
                        .param("track", "양호").param("odds_1", "3.0").param("odds_2", "4.0").param("odds_3", "6.0"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/r/20261115/1/7").param("mode", "day"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("마지막 입력")))
                .andExpect(content().string(not(containsString("운영자"))))
                .andExpect(content().string(not(containsString("텔레그램"))))
                .andExpect(content().string(not(containsString("배당·마체중 입력"))));
        mvc.perform(get("/r/20261115/1/7").param("mode", "day").with(user("운영자").roles("ADMIN")))
                .andExpect(content().string(containsString("운영자")));
    }
}
