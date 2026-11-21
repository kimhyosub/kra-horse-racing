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
        r.addResourceHandler("/uploads/**").addResourceLocations(uploads.getDir().toURI().toString()).setCachePeriod(60 * 60 * 24 * 30);
    }
}
