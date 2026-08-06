package com.healthconnexx.hcxssfhirimport.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HDC-221: S3 operations for FHIR file processing.
 * Lists .json files under the fhir/ prefix, downloads content, and moves
 * files to processed/ or error/ by CopyObject + DeleteObject.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FhirS3Service {

    private final S3Client s3Client;

    @Value("${fhir.s3.bucket}")
    private String bucket;

    @Value("${fhir.s3.fhir-prefix:fhir/}")
    private String fhirPrefix;

    @Value("${fhir.s3.processed-prefix:processed/}")
    private String processedPrefix;

    @Value("${fhir.s3.error-prefix:error/}")
    private String errorPrefix;

    /** HDC-221: Lists all .json object keys under the fhir/ prefix. */
    public List<String> listFhirFiles() {
        log.debug("HDC-221: Listing FHIR files in s3://{}/{}", bucket, fhirPrefix);
        List<String> keys = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(fhirPrefix)
                        .build())
                .contents().stream()
                .map(S3Object::key)
                .filter(k -> k.endsWith(".json"))
                .filter(k -> !k.equals(fhirPrefix))  // skip folder key itself
                .toList();
        log.info("HDC-221: Found {} FHIR file(s) to process in s3://{}/{}", keys.size(), bucket, fhirPrefix);
        return keys;
    }

    /** HDC-221: Downloads a FHIR file from S3 as a UTF-8 string. */
    public String download(String key) {
        log.debug("HDC-221: Downloading s3://{}/{}", bucket, key);
        byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()).asByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** HDC-221: Moves a file from fhir/ to processed/ (copy then delete). */
    public void moveToProcessed(String key) {
        String filename = filename(key);
        String destination = processedPrefix + filename;
        move(key, destination);
        log.info("HDC-221: Moved s3://{}/{} → {}", bucket, key, destination);
    }

    /** HDC-221: Moves a file from fhir/ to error/ (copy then delete). */
    public void moveToError(String key) {
        String filename = filename(key);
        String destination = errorPrefix + filename;
        move(key, destination);
        log.info("HDC-221: Moved s3://{}/{} → {}", bucket, key, destination);
    }

    private void move(String sourceKey, String destinationKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(destinationKey)
                .build());
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(sourceKey)
                .build());
    }

    private String filename(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }
}
