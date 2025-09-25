package com.mesh.deployment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    
    private String awsRegion;
    private String awsAccessKey;
    private String awsSecretKey;
    private String s3Bucket;
    
    private Deployment deployment = new Deployment();
    
    @Data
    public static class Deployment {
        private String instanceType = "t3.medium";
        private String amiId = "ami-0c02fb55956c7d316";
        private String securityGroupId;
        private String subnetId;
        private String keyName;
        private int inferencePort = 5000;
        private String healthCheckPath = "/health";
        private String predictPath = "/predict";
    }
}
