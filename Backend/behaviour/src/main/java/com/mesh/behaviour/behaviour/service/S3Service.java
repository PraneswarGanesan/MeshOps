// src/main/java/com/mesh/behaviour/behaviour/service/S3Service.java
package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.config.AppProperties;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final AppProperties props;
    private final S3Client s3;
    private final S3Presigner presigner;

    public String getBucketName() {
        String raw = props.getAwsS3Bucket();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("AWS S3 bucket name not configured");
        }
        return raw.trim().replaceFirst("^s3://", "").replaceAll("/+$", "");
    }

    /**
     * Safe reader: limit number of characters and lines when fetching large objects.
     */
    public String getStringSafe(String key, int maxChars, int maxLines) {
        try (ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(
                GetObjectRequest.builder()
                        .bucket(getBucketName())
                        .key(key)
                        .build())) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(s3Object, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;

            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                lines++;

                if (sb.length() >= maxChars || lines >= maxLines) {
                    break;
                }
            }
            return sb.toString();

        } catch (Exception e) {
            return ""; // safe fallback
        }
    }

    /* -------------------- Basic put/get (text) -------------------- */

    public String putString(String key, String content, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(getBucketName())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8))
        );
        return key;
    }

    public void copy(String srcKey, String destKey) {
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(getBucketName())
                .sourceKey(srcKey)
                .destinationBucket(getBucketName())
                .destinationKey(destKey)
                .build());
    }

    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(getBucketName())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public String getString(String key) {
        try (ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(
                GetObjectRequest.builder()
                        .bucket(getBucketName())
                        .key(key)
                        .build())) {
            return new BufferedReader(new InputStreamReader(s3Object, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read object from S3: " + key, e);
        }
    }

    public String putBytes(String key, byte[] bytes, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(getBucketName())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes)
        );
        return key;
    }

    public byte[] getBytes(String key) {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                GetObjectRequest.builder()
                        .bucket(getBucketName())
                        .key(key)
                        .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read bytes from S3: " + key, e);
        }
    }

    public URL presignGet(String key, Duration ttl) {
        var req = GetObjectRequest.builder()
                .bucket(getBucketName())
                .key(key)
                .build();
        var presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .getObjectRequest(req)
                        .signatureDuration(ttl)
                        .build());
        return presigned.url();
    }

    public Map<String, URL> listStandardArtifactUrls(String artifactsPrefix, Duration ttl) {
        Map<String, URL> out = new LinkedHashMap<>();
        String[] names = {"metrics.json", "tests.csv", "confusion_matrix.png", "logs.txt"};
        for (String n : names) {
            String key = S3KeyUtil.join(artifactsPrefix, n);
            if (exists(key)) {
                out.put(n, presignGet(key, ttl));
            }
        }
        return out;
    }

    public List<String> listKeys(String prefix) {
        var req = ListObjectsV2Request.builder()
                .bucket(getBucketName())
                .prefix(prefix)
                .build();
        var out = new ArrayList<String>();
        ListObjectsV2Response resp;
        String token = null;
        do {
            resp = s3.listObjectsV2(req.toBuilder().continuationToken(token).build());
            resp.contents().forEach(o -> out.add(o.key()));
            token = resp.nextContinuationToken();
        } while (resp.isTruncated());
        return out;
    }

    public boolean existsPrefix(String prefix) {
        var resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(getBucketName())
                .prefix(prefix)
                .maxKeys(1)
                .build());
        Integer kc = resp.keyCount();
        return kc != null && kc > 0;
    }

    public void deletePrefix(String prefix) {
        var keys = listKeys(prefix);
        if (keys.isEmpty()) return;
        for (int i = 0; i < keys.size(); i += 1000) {
            var slice = keys.subList(i, Math.min(i + 1000, keys.size()));
            var del = DeleteObjectsRequest.builder()
                    .bucket(getBucketName())
                    .delete(Delete.builder()
                            .objects(slice.stream()
                                    .map(k -> ObjectIdentifier.builder().key(k).build())
                                    .toList())
                            .build())
                    .build();
            s3.deleteObjects(del);
        }
    }
}
