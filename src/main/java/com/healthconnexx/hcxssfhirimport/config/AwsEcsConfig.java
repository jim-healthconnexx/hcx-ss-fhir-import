package com.healthconnexx.hcxssfhirimport.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;

// HDC-265: EcsClient bean — only created when aws.ecs.enabled=true. Uses DefaultCredentialsProvider
//          so QA/prod rely on the ECS task IAM role, matching the existing S3/Secrets Manager pattern.
@Slf4j
@Configuration
public class AwsEcsConfig {

    private final String region;

    public AwsEcsConfig(@Value("${aws.region:us-east-1}") String region) {
        this.region = region;
    }

    @Bean
    @ConditionalOnProperty(name = "aws.ecs.enabled", havingValue = "true")
    public EcsClient ecsClient() {
        log.info("HDC-265: Creating EcsClient with DefaultCredentialsProvider for region {}", region);
        return EcsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
