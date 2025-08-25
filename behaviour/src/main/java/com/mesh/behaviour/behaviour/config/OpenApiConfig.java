package com.mesh.behaviour.behaviour.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger / OpenAPI UI config. Visit /swagger-ui/index.html after startup. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI behaviourOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Behaviour Service API")
                        .description("Approve driver/tests, run on EC2 via SSM, fetch artifacts from S3.")
                        .version("v1"));
    }
}
