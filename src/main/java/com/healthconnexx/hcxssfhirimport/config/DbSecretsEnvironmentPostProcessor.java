package com.healthconnexx.hcxssfhirimport.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * HDC-221: EnvironmentPostProcessor that fetches DB credentials from AWS Secrets Manager
 * and injects them as spring.datasource.* properties before DataSource auto-configuration.
 *
 * <p>Only activates when {@code aws.secrets.db-credentials-arn} is set (e.g. application-qa.properties).
 * The secret must be JSON with keys: username, password, host, port, dbname.
 */
public class DbSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    // HDC-221: Cannot use @Slf4j here — runs before Spring context initialises.
    private static final Logger log = LoggerFactory.getLogger(DbSecretsEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "awsSecretsManagerDbCredentials";
    private static final String ARN_PROPERTY = "aws.secrets.db-credentials-arn";
    private static final String REGION_PROPERTY = "aws.region";
    private static final String DEFAULT_REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] activeProfiles = environment.getActiveProfiles();
        String secretArn = environment.getProperty(ARN_PROPERTY);
        String arnStatus = StringUtils.hasText(secretArn) ? "[set]" : "[blank/not-found]";

        System.err.println("HDC-221: DbSecretsEnvironmentPostProcessor started — activeProfiles="
                + Arrays.toString(activeProfiles) + " " + ARN_PROPERTY + "=" + arnStatus);
        log.info("HDC-221: DbSecretsEnvironmentPostProcessor started — activeProfiles={} {}={}",
                Arrays.toString(activeProfiles), ARN_PROPERTY, arnStatus);

        if (!StringUtils.hasText(secretArn)) {
            System.err.println("HDC-221: WARN — " + ARN_PROPERTY
                    + " is blank; skipping Secrets Manager DB credential injection. Profiles: "
                    + Arrays.toString(activeProfiles));
            log.warn("HDC-221: {} is blank; skipping Secrets Manager DB credential injection. Profiles: {}",
                    ARN_PROPERTY, Arrays.toString(activeProfiles));
            return;
        }

        String region = environment.getProperty(REGION_PROPERTY, DEFAULT_REGION);
        log.info("HDC-221: Fetching DB credentials from Secrets Manager ARN {} in region {}", secretArn, region);

        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            GetSecretValueResponse response = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretArn).build());

            Map<String, String> secretMap = objectMapper.readValue(
                    response.secretString(), new TypeReference<Map<String, String>>() {});

            String host = requiredKey(secretMap, "host", secretArn);
            String port = requiredKey(secretMap, "port", secretArn);
            String dbname = requiredKey(secretMap, "dbname", secretArn);
            String username = requiredKey(secretMap, "username", secretArn);
            String password = requiredKey(secretMap, "password", secretArn);

            String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, dbname);

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbcUrl);
            props.put("spring.datasource.username", username);
            props.put("spring.datasource.password", password);

            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));

            log.info("HDC-221: DB credentials injected — url={}, username={}, password=***", jdbcUrl, username);
            System.err.println("HDC-221: DB credentials injected — url=" + jdbcUrl + " username=" + username);

        } catch (Exception e) {
            log.error("HDC-221: Failed to fetch DB credentials from Secrets Manager ARN {}: {}",
                    secretArn, e.getMessage(), e);
            throw new IllegalStateException(
                    "HDC-221: Cannot start — DB credentials unavailable from Secrets Manager: " + secretArn, e);
        }
    }

    private String requiredKey(Map<String, String> map, String key, String arn) {
        String value = map.get(key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    String.format("HDC-221: Secret at %s is missing required key '%s'", arn, key));
        }
        return value;
    }
}
