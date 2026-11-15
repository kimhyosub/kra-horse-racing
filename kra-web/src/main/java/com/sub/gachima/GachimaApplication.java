package com.sub.gachima;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true)
public class GachimaApplication {
    public static void main(String[] args) {
        SpringApplication.run(GachimaApplication.class, args);
    }
}
