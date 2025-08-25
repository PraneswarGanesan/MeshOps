package com.mesh.behaviour.behaviour.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.ssm.SsmClient;

@Configuration
@RequiredArgsConstructor
public class AwsConfig {

    private final AppProperties props;

    private Region resolveRegion() {
        String r = props.getAwsRegion();
        if (r == null || r.isBlank()) {
            return Region.US_EAST_1;
        }
        return Region.of(r);
    }

    private StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        props.getAwsAccessKey(),
                        props.getAwsSecretKey()
                )
        );
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(resolveRegion())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(resolveRegion())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public Ec2Client ec2Client() {
        return Ec2Client.builder()
                .region(resolveRegion())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(resolveRegion())
                .credentialsProvider(credentialsProvider())
                .build();
    }
}
