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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 검색·광고: 메타태그, sitemap, robots, ads.txt (애드센스 ID 를 넣은 상태) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seo;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-seo",
        "gachima.auto-import=false",
        "gachima.public-url=https://gachima.example.com/",
        "gachima.ads.client=ca-pub-1234567890123456",
        "gachima.ads.gsc-verify=abcVERIFY",
        "gachima.ads.contact=help@example.com"})
@AutoConfigureMockMvc
class SeoTest {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;

    @BeforeEach
    void load() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());
    }

    @Test
    void metaTagsAndFiles() throws Exception {
        mvc.perform(get("/r/20261115/1/7")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>11월 15일(일) 서울 7경주 예상·분석 | 가치마</title>")))
                .andExpect(content().string(containsString("<meta name=\"description\" content=\"11월 15일(일) 서울 7경주")))
                .andExpect(content().string(containsString("전날 예상 1위")))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://gachima.example.com/r/20261115/1/7\"")))
                .andExpect(content().string(containsString("https://gachima.example.com/og.png")))
                .andExpect(content().string(containsString("<meta name=\"google-adsense-account\" content=\"ca-pub-1234567890123456\"")))
                .andExpect(content().string(containsString("adsbygoogle.js?client=ca-pub-1234567890123456")))
                .andExpect(content().string(containsString("<meta name=\"google-site-verification\" content=\"abcVERIFY\"")));
        mvc.perform(get("/r/20261115/1/7/score")).andExpect(status().isOk()).andExpect(content().string(containsString("요인별 점수표")));
        mvc.perform(get("/stats")).andExpect(status().isOk()).andExpect(content().string(containsString("공개 성적 | 가치마")));
        mvc.perform(get("/about")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Google 애드센스")))
                .andExpect(content().string(containsString("help@example.com")));
        mvc.perform(get("/login")).andExpect(content().string(containsString("noindex")));

        mvc.perform(get("/ads.txt")).andExpect(status().isOk())
                .andExpect(content().string("google.com, pub-1234567890123456, DIRECT, f08c47fec0942fa0\n"));
        mvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sitemap: https://gachima.example.com/sitemap.xml")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<loc>https://gachima.example.com/r/20261115/1/7</loc><lastmod>2026-11-15</lastmod>")))
                .andExpect(content().string(not(containsString("/admin"))));
        mvc.perform(get("/og.png")).andExpect(status().isOk());
    }
}
