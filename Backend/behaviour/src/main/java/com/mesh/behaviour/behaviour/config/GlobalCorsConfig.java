// src/main/java/com/mesh/behaviour/behaviour/config/GlobalCorsConfig.java
package com.mesh.behaviour.behaviour.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.*;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class GlobalCorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://localhost:5173"); // Vite dev origin
        config.addAllowedOrigin("http://localhost:3000"); // React dev origin
        config.addAllowedOrigin("http://127.0.0.1:5173"); // Alternative localhost
        config.addAllowedOrigin("http://127.0.0.1:3000"); // Alternative localhost
        config.addAllowedOriginPattern("*"); // Allow all origins for development
        config.addAllowedHeader(CorsConfiguration.ALL);
        config.addAllowedMethod(CorsConfiguration.ALL);
        config.setAllowCredentials(false); // set true only if you use cookies/credentials

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
