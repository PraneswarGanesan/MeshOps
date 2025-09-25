package com.mesh.unittest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class UnitTestServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnitTestServiceApplication.class, args);
    }
}
