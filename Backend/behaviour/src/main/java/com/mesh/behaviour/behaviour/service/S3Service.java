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

    /* -------------------- Basic put/get (text) -------------------- */

    public String putString(String key, String content, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.getAwsS3Bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8))
        );
        return key;
    }

    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.getAwsS3Bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            // If any other error, treat as not-exists for MVP
            return false;
        }
    }

    /** Read whole object as UTF-8 text (driver.py, tests.yaml, metrics.json, etc.). */
    public String getString(String key) {
        try (ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(
                GetObjectRequest.builder()
                        .bucket(props.getAwsS3Bucket())
                        .key(key)
                        .build())) {
            return new BufferedReader(new InputStreamReader(s3Object, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read object from S3: " + key, e);
        }
    }

    /**
     * Read text safely for prompts: sample CSV lines and/or truncate to maxChars.
     * Useful when sending file snippets to an LLM.
     */
    public String getStringSafe(String key, int maxChars, int csvMaxLines) {
        String text = getString(key);
        String lower = key.toLowerCase();
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            String sampled = text.lines()
                    .limit(csvMaxLines)
                    .collect(Collectors.joining("\n"));
            return sampled.length() > maxChars
                    ? sampled.substring(0, maxChars) + "\n# ...truncated..."
                    : sampled;
        }
        return text.length() > maxChars
                ? text.substring(0, maxChars) + "\n# ...truncated..."
                : text;
    }

    /* -------------------- Binary helpers -------------------- */

    public String putBytes(String key, byte[] bytes, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.getAwsS3Bucket())
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
                        .bucket(props.getAwsS3Bucket())
                        .key(key)
                        .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read bytes from S3: " + key, e);
        }
    }

    /* -------------------- Presign (GET) -------------------- */

    public URL presignGet(String key, Duration ttl) {
        var req = GetObjectRequest.builder()
                .bucket(props.getAwsS3Bucket())
                .key(key)
                .build();
        var presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .getObjectRequest(req)
                        .signatureDuration(ttl)
                        .build());
        return presigned.url();
    }

    /** Returns presigned URLs for standard artifacts if they exist. */
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

    /* -------------------- Listing & cleanup -------------------- */

    /** List all object keys under a prefix. */
    public List<String> listKeys(String prefix) {
        var req = ListObjectsV2Request.builder()
                .bucket(props.getAwsS3Bucket())
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

    /** True if any object exists under the prefix. */
    public boolean existsPrefix(String prefix) {
        var resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(props.getAwsS3Bucket())
                .prefix(prefix)
                .maxKeys(1)
                .build());
        Integer kc = resp.keyCount();
        return kc != null && kc > 0;
    }

    /** Delete all objects under a prefix (chunked by 1000). */
    public void deletePrefix(String prefix) {
        var keys = listKeys(prefix);
        if (keys.isEmpty()) return;
        for (int i = 0; i < keys.size(); i += 1000) {
            var slice = keys.subList(i, Math.min(i + 1000, keys.size()));
            var del = DeleteObjectsRequest.builder()
                    .bucket(props.getAwsS3Bucket())
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
