package com.ssuai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SsuaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsuaiApplication.class, args);
    }
}
