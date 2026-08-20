package com.healthconnexx.hcxssfhirimport.service;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;

/**
 * HDC-265: Triggers the hcx-ss-export-processor ECS task after a successful FHIR import.
 * Only active when aws.ecs.enabled=true; absent from the application context otherwise.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aws.ecs.enabled", havingValue = "true")
public class EcsTaskService {

    private final EcsClient ecsClient;
    private final String clusterArn;
    private final String taskDefinitionArn;
    private final List<String> subnets;
    private final List<String> securityGroups;
    private final AssignPublicIp assignPublicIp;

    public EcsTaskService(
            EcsClient ecsClient,
            @Value("${aws.ecs.cluster-arn}") String clusterArn,
            @Value("${aws.ecs.task-definition-arn}") String taskDefinitionArn,
            @Value("${aws.ecs.subnets}") String subnets,
            @Value("${aws.ecs.security-groups}") String securityGroups,
            @Value("${aws.ecs.assign-public-ip:ENABLED}") String assignPublicIp) {
        this.ecsClient = ecsClient;
        this.clusterArn = clusterArn;
        this.taskDefinitionArn = taskDefinitionArn;
        this.subnets = Arrays.asList(subnets.split(","));
        this.securityGroups = Arrays.asList(securityGroups.split(","));
        this.assignPublicIp = AssignPublicIp.fromValue(assignPublicIp);
    }

    /**
     * HDC-265: Runs the hcx-ss-export-processor ECS task.
     * Logs the launched task ARN on success; logs and swallows errors so a trigger
     * failure does not retroactively fail an otherwise-successful FHIR import.
     */
    public void runExportProcessor() {
        log.info("HDC-265: Launching ECS task '{}' on cluster '{}'", taskDefinitionArn, clusterArn);
        try {
            RunTaskResponse response = ecsClient.runTask(RunTaskRequest.builder()
                    .cluster(clusterArn)
                    .taskDefinition(taskDefinitionArn)
                    .launchType(LaunchType.FARGATE)
                    .networkConfiguration(NetworkConfiguration.builder()
                            .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                    .subnets(subnets)
                                    .securityGroups(securityGroups)
                                    .assignPublicIp(assignPublicIp)
                                    .build())
                            .build())
                    .build());

            response.tasks().stream()
                    .map(Task::taskArn)
                    .forEach(arn -> log.info("HDC-265: ECS task launched — taskArn={}", arn));

            if (!response.failures().isEmpty()) {
                response.failures().forEach(f ->
                        log.error("HDC-265: ECS RunTask failure — reason='{}' arn='{}'", f.reason(), f.arn()));
            }
        } catch (Exception e) {
            log.error("HDC-265: Failed to launch ECS export processor task — import result is unaffected.", e);
        }
    }
}
