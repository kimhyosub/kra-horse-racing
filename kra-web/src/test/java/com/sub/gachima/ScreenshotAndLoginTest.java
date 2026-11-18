package com.sub.gachima;

import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.ScreenshotReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 로그인 막기 + 스크린샷 → 입력칸 채우기 (Claude 호출은 가짜로) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shot;DB_CLOSE_DELAY=-1",
        "gachima.import-dir=target/test-import-shot",
        "gachima.auto-import=false",
        "gachima.login.user=sub",
        "gachima.login.password=test-pass-123"})
@AutoConfigureMockMvc
class ScreenshotAndLoginTest {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;
    @MockBean ScreenshotReader reader;

    @BeforeEach
    void load() throws Exception {
        importService.importFile(new ClassPathResource("day-20261115.json").getFile());
    }

    @Test
    void pagesNeedLogin() throws Exception {
        mvc.perform(get("/r/20261115/1/7")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/stats")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/r/20261115/1/7/input").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("로그인 유지")));
        mvc.perform(get("/css/gachima.css")).andExpect(status().isOk());

        mvc.perform(formLogin().user("sub").password("wrong")).andExpect(unauthenticated());
        mvc.perform(formLogin().user("sub").password("test-pass-123")).andExpect(authenticated());
    }

    @Test
    @WithMockUser
    void screenshotFillsInputs() throws Exception {
        ScreenshotReader.Reading r = new ScreenshotReader.Reading();
        r.raceNoSeen = 7;
        r.track = "포화";
        r.odds.put(1, 8.7); r.odds.put(2, 13.8); r.odds.put(8, 15.4);
        r.bwd.put(8, -7.0);
        when(reader.isEnabled()).thenReturn(true);
        when(reader.read(anyList(), anyString(), eq(7), anyMap())).thenReturn(r);

        MockMultipartFile png = new MockMultipartFile("files", "odds.png", "image/png", new byte[]{1, 2, 3});
        MvcResult res = mvc.perform(multipart("/r/20261115/1/7/screenshot").file(png).with(csrf()))
                .andExpect(redirectedUrl("/r/20261115/1/7?mode=day&edit=true"))
                .andExpect(flash().attributeExists("shot"))
                .andReturn();

        mvc.perform(get("/r/20261115/1/7").param("mode", "day").param("edit", "true")
                        .flashAttrs(res.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3두 배당을 읽었습니다")))
                .andExpect(content().string(containsString("배당을 못 읽은 마번")))
                .andExpect(content().string(containsString("value=\"15.4\"")))
                .andExpect(content().string(containsString("value=\"-7\"")))
                .andExpect(content().string(containsString("selected=\"selected\">포화</option>")));
    }

    @Test
    @WithMockUser
    void screenshotOffWithoutKey() throws Exception {
        when(reader.isEnabled()).thenReturn(false);
        mvc.perform(get("/r/20261115/1/7").param("mode", "day"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("스크린샷으로 채우기"))));
        MockMultipartFile png = new MockMultipartFile("files", "odds.png", "image/png", new byte[]{1});
        mvc.perform(multipart("/r/20261115/1/7/screenshot").file(png).with(csrf()))
                .andExpect(flash().attribute("shotError", containsString("ANTHROPIC_API_KEY")));
    }
}
