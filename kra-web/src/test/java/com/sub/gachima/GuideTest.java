package com.sub.gachima;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 가이드: 처음 실행 때 guides/*.md 가 글로 들어가고, 누구나 읽고, 관리자만 쓴다 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:guide;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-guide",
        "gachima.auto-import=false",
        "gachima.login.user=sub",
        "gachima.login.password=test-pass-123",
        "gachima.upload-dir=target/test-uploads"})
@AutoConfigureMockMvc
class GuideTest {

    @Autowired MockMvc mvc;

    @Test
    void seededGuidesArePublic() throws Exception {
        mvc.perform(get("/guide")).andExpect(status().isOk())
                .andExpect(content().string(containsString("가치마 보는 법")))
                .andExpect(content().string(containsString("기대값이란?")))
                .andExpect(content().string(containsString("승식 정리")))
                .andExpect(content().string(containsString("href=\"/guide/paper-ledger\"")));
        mvc.perform(get("/guide/expected-value")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<h2>기대값 = 우승 확률 × 배당</h2>")))
                .andExpect(content().string(containsString("<title>기대값이란? — 잘 맞히는 것과 돈이 되는 것은 다르다 · 가치마 가이드</title>")))
                .andExpect(content().string(containsString("<meta name=\"description\" content=\"우승 확률 × 배당으로")))
                .andExpect(content().string(containsString("다른 기초 가이드")))
                .andExpect(content().string(not(containsString("고치기"))));
        mvc.perform(get("/guide/bet-types")).andExpect(content().string(containsString("<div class=\"post-table\"><table>")));
        mvc.perform(get("/guide/expected-value"))
                .andExpect(content().string(containsString("<figure><img src=\"/guide-img/ev-curve.png\"")))
                .andExpect(content().string(containsString("<figcaption>")))
                .andExpect(content().string(containsString("/guide-img/ev-curve.png\">")));            // 공유 미리보기 = 대표 이미지
        mvc.perform(get("/guide")).andExpect(content().string(containsString("class=\"gc-thumb\"")))
                .andExpect(content().string(not(containsString("가치마 개발기"))));                  // 개발기는 초안
        mvc.perform(get("/guide/gachima-story")).andExpect(status().isNotFound());
        mvc.perform(get("/guide/gachima-story").with(user("sub").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("비공개 초안")));
        mvc.perform(get("/guide-img/calibration.png")).andExpect(status().isOk());
        mvc.perform(get("/guide/none")).andExpect(status().isNotFound());
        mvc.perform(get("/sitemap.xml")).andExpect(content().string(containsString("/guide/how-to-read</loc>")));
        mvc.perform(get("/admin/posts")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void categoriesAndNewestFirst() throws Exception {
        String all = mvc.perform(get("/guide")).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"gtabs\"")))
                .andReturn().getResponse().getContentAsString();
        // 전체: 최신 글이 위 (주간 총평 → 데이터 분석 → 기초 가이드)
        int w = all.indexOf("/guide/weekly-2026-11-22"), a = all.indexOf("/guide/favorites-roi"), b = all.indexOf("/guide/how-to-read");
        org.junit.jupiter.api.Assertions.assertTrue(w > 0 && w < a && a < b, "최신 글이 위");
        // 기초 가이드 탭: 읽는 순서(order), 분석 글은 없음
        String basic = mvc.perform(get("/guide").param("cat", "basic")).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(basic.indexOf("/guide/how-to-read") < basic.indexOf("/guide/expected-value"));
        org.junit.jupiter.api.Assertions.assertFalse(basic.contains("/guide/favorites-roi"));
        mvc.perform(get("/guide").param("cat", "weekly"))
                .andExpect(content().string(containsString("/guide/weekly-2026-11-22")))
                .andExpect(content().string(not(containsString("/guide/how-to-read\""))));
        mvc.perform(get("/guide").param("cat", "nope")).andExpect(status().isOk());              // 모르는 분류 = 전체
        mvc.perform(get("/guide/box-betting"))
                .andExpect(content().string(containsString("데이터 분석")))
                .andExpect(content().string(containsString("다른 데이터 분석")))
                .andExpect(content().string(containsString("2026.11.22")));
    }

    @Test
    void imageUpload() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/admin/posts/upload")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", png))
                        .with(csrf()).with(user("sub").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = res.replaceAll(".*\"url\":\"([^\"]+)\".*", "$1");
        org.junit.jupiter.api.Assertions.assertTrue(url.startsWith("/uploads/"), res);
        mvc.perform(get(url)).andExpect(status().isOk());                                            // 방문자도 볼 수 있음
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/admin/posts/upload")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.txt", "text/plain", "hello world!".getBytes()))
                        .with(csrf()).with(user("sub").roles("ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("이미지만")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/admin/posts/upload")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", png)).with(csrf()))
                .andExpect(status().is3xxRedirection());                                             // 로그인 필요
    }

    @Test
    void adminWritesDraftThenPublishes() throws Exception {
        mvc.perform(post("/admin/posts").with(csrf()).with(user("sub").roles("ADMIN"))
                        .param("slug", "my-note").param("title", "운영 노트").param("summary", "요약")
                        .param("body", "# 소제목\n\n<b>태그는 글자로</b> **굵게**").param("sortOrder", "5"))
                .andExpect(redirectedUrl("/guide/my-note"));
        mvc.perform(get("/guide/my-note")).andExpect(status().isNotFound());              // 초안: 방문자에겐 없음
        mvc.perform(get("/guide/my-note").with(user("sub").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("비공개 초안")))
                .andExpect(content().string(containsString("&lt;b&gt;태그는 글자로&lt;/b&gt; <b>굵게</b>")));
        mvc.perform(post("/admin/posts").with(csrf()).with(user("sub").roles("ADMIN"))
                        .param("slug", "한글주소").param("title", "x"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("영문 소문자")));
        mvc.perform(post("/admin/posts").with(csrf()).with(user("sub").roles("ADMIN"))
                        .param("slug", "expected-value").param("title", "중복"))
                .andExpect(content().string(containsString("같은 주소의 글이 이미 있습니다")));
        mvc.perform(post("/admin/posts").with(csrf()).with(user("friend").roles("INPUT"))
                        .param("slug", "x-y").param("title", "x")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/posts").with(user("sub").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("운영 노트")))
                .andExpect(content().string(containsString("초안")));
    }
}
