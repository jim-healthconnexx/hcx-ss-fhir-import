package com.healthconnexx.hcxssfhirimport.config;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

// HDC-221: S3Client bean — static credentials for LocalStack, DefaultCredentialsProvider for QA/prod.
@Slf4j
@Configuration
public class AwsS3Config {

    private final String region;
    private final String s3Endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;

    public AwsS3Config(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.s3.endpoint:}") String s3Endpoint,
            @Value("${aws.access-key-id:}") String accessKeyId,
            @Value("${aws.secret-access-key:}") String secretAccessKey) {
        this.region = region;
        this.s3Endpoint = s3Endpoint;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
    }

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));

        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            log.info("HDC-221: Creating S3 client with static credentials");
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        } else {
            log.info("HDC-221: Creating S3 client with default AWS credential provider chain");
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        if (StringUtils.hasText(s3Endpoint)) {
            log.info("HDC-221: Configuring S3 client with endpoint override: {}", s3Endpoint);
            builder.endpointOverride(URI.create(s3Endpoint))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }

        return builder.build();
    }
}
