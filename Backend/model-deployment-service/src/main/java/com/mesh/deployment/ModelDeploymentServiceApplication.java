package com.mesh.deployment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ModelDeploymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelDeploymentServiceApplication.class, args);
    }
}
