package com.company.crbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CrbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrbotApplication.class, args);
    }
}
