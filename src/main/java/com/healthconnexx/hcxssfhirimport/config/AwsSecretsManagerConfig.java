package com.healthconnexx.hcxssfhirimport.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

// HDC-221: SecretsManagerClient bean using DefaultCredentialsProvider so QA/prod rely on ECS task IAM role.
@Slf4j
@Configuration
public class AwsSecretsManagerConfig {

    private final String region;

    public AwsSecretsManagerConfig(@Value("${aws.region:us-east-1}") String region) {
        this.region = region;
    }

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        log.info("HDC-221: Creating SecretsManagerClient with DefaultCredentialsProvider for region {}", region);
        return SecretsManagerClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
