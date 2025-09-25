package com.mesh.unittest.service;

import com.mesh.unittest.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AppProperties appProperties;

    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(appProperties.getS3Bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("Error checking if S3 key exists: {}", key, e);
            return false;
        }
    }

    public String getString(String key) {
        try {
            GetObjectResponse response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(appProperties.getS3Bucket())
                    .key(key)
                    .build());
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error reading S3 object: {}", key, e);
            throw new RuntimeException("Failed to read S3 object: " + key, e);
        }
    }

    public String getStringSafe(String key, int maxBytes, int maxLines) {
        try {
            if (!exists(key)) return "";
            
            GetObjectResponse response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(appProperties.getS3Bucket())
                    .key(key)
                    .range("bytes=0-" + (maxBytes - 1))
                    .build());
            
            String content = new String(response.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            if (lines.length > maxLines) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < maxLines; i++) {
                    sb.append(lines[i]).append("\n");
                }
                return sb.toString();
            }
            
            return content;
        } catch (Exception e) {
            log.warn("Error reading S3 object safely: {}", key, e);
            return "";
        }
    }

    public void putString(String key, String content, String contentType) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(appProperties.getS3Bucket())
                    .key(key)
                    .contentType(contentType != null ? contentType : "text/plain")
                    .build(),
                    RequestBody.fromString(content));
            log.debug("Successfully uploaded to S3: {}", key);
        } catch (Exception e) {
            log.error("Error uploading to S3: {}", key, e);
            throw new RuntimeException("Failed to upload to S3: " + key, e);
        }
    }

    public boolean existsPrefix(String prefix) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(appProperties.getS3Bucket())
                    .prefix(prefix)
                    .maxKeys(1)
                    .build());
            return !response.contents().isEmpty();
        } catch (Exception e) {
            log.error("Error checking S3 prefix: {}", prefix, e);
            return false;
        }
    }

    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(appProperties.getS3Bucket())
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response response;
            do {
                response = s3Client.listObjectsV2(request);
                response.contents().forEach(obj -> keys.add(obj.key()));
                request = request.toBuilder().continuationToken(response.nextContinuationToken()).build();
            } while (response.isTruncated());

        } catch (Exception e) {
            log.error("Error listing S3 keys with prefix: {}", prefix, e);
        }
        return keys;
    }

    public String getPresignedUrl(String key, Duration expiration) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(appProperties.getS3Bucket())
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiration)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        } catch (Exception e) {
            log.error("Error generating presigned URL for: {}", key, e);
            throw new RuntimeException("Failed to generate presigned URL: " + key, e);
        }
    }

    public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(appProperties.getS3Bucket())
                    .key(key)
                    .build());
            log.debug("Successfully deleted S3 object: {}", key);
        } catch (Exception e) {
            log.error("Error deleting S3 object: {}", key, e);
            throw new RuntimeException("Failed to delete S3 object: " + key, e);
        }
    }
}
