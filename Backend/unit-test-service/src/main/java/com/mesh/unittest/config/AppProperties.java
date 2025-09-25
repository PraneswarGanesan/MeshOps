package com.mesh.unittest.config;

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
    
    private Runner runner = new Runner();
    
    @Data
    public static class Runner {
        private String s3CodePath = "code/meshops/runner.py";
        private String instanceType = "t3.medium";
        private String amiId = "ami-0c02fb55956c7d316";
        private String securityGroupId;
        private String subnetId;
        private String keyName;
    }
}
