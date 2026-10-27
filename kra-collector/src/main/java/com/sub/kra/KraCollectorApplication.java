package com.sub.kra;

import com.sub.kra.config.KraProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(KraProperties.class)
public class KraCollectorApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(KraCollectorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); // 배치형 실행
        System.exit(SpringApplication.exit(app.run(args)));
    }
}
