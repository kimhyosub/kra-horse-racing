package com.sub.gachima.config;

import com.sub.gachima.service.UploadService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 올린 이미지(/uploads/**)를 업로드 폴더에서 바로 보여준다 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final UploadService uploads;

    public WebConfig(UploadService uploads) { this.uploads = uploads; }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry r) {
        // 폴더가 아직 없으면 toURI() 끝에 '/' 가 안 붙어 파일을 못 찾으므로 만들고, '/' 를 보장한다
        uploads.getDir().mkdirs();
        String loc = uploads.getDir().getAbsoluteFile().toURI().toString();
        if (!loc.endsWith("/")) loc += "/";
        r.addResourceHandler("/uploads/**").addResourceLocations(loc).setCachePeriod(60 * 60 * 24 * 30);
    }
}
