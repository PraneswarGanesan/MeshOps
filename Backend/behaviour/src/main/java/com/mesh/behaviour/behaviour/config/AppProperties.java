package com.mesh.behaviour.behaviour.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class AppProperties {

    // --- AWS core ---
    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.s3.bucket}")
    private String awsS3Bucket;

    @Value("${aws.access.key}")
    private String awsAccessKey;

    @Value("${aws.secret.key}")
    private String awsSecretKey;

    // --- EC2 / SSM runner settings ---
    @Value("${aws.ec2.ami}")
    private String awsEc2Ami;

    @Value("${aws.ec2.instanceType}")
    private String awsEc2InstanceType;

    @Value("${aws.ec2.iamInstanceProfile}")
    private String awsEc2IamInstanceProfile;

    @Value("${aws.ec2.securityGroupIds}")
    private String awsEc2SecurityGroupIds;

    @Value("${aws.ec2.subnetId}")
    private String awsEc2SubnetId;

    // --- Optional LLM (Gemini) ---
    @Value("${llm.gemini.apiKey}")
    private String llmGeminiApiKey;

    @Value("${llm.gemini.url}")
    private String llmGeminiUrl;
}
