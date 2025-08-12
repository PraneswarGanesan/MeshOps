package com.example.repo.service;

import com.example.repo.dto.FileItem;
import com.example.repo.dto.FolderItem;
import com.example.repo.dto.TreeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RepositoryService {

    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucketName;

    public RepositoryService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public void createProject(String username, String projectName) {
        String prefix = userProjectPrefix(username, projectName);
        ensureFolderMarker(prefix);
    }

    public List<String> listProjects(String username) {
        String prefix = ensureTrailingSlash(username);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .delimiter("/")
                .build();
        ListObjectsV2Response response = s3Client.listObjectsV2(request);
        return response.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .map(p -> p.substring(prefix.length()))
                .map(this::stripTrailingSlash)
                .sorted()
                .collect(Collectors.toList());
    }

    public TreeResponse listTree(String username, String projectName, String path) {
        String normalizedPath = normalizePath(path);
        String basePrefix = userProjectPrefix(username, projectName);
        String prefix = basePrefix + (normalizedPath.isEmpty() ? "" : normalizedPath + "/");

        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .delimiter("/")
                .build();
        ListObjectsV2Response response = s3Client.listObjectsV2(request);

        List<FolderItem> folders = response.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .map(p -> p.substring(basePrefix.length()))
                .map(this::stripTrailingSlash)
                .map(name -> new FolderItem(name, concatPath(normalizedPath, name)))
                .sorted(Comparator.comparing(FolderItem::getName))
                .collect(Collectors.toList());

        List<FileItem> files = response.contents().stream()
                .map(obj -> toFileItem(basePrefix, normalizedPath, obj))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(FileItem::getName))
                .collect(Collectors.toList());

        return new TreeResponse(username, projectName, normalizedPath, folders, files);
    }

    public List<String> uploadFiles(String username, String projectName, String path, List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        String normalizedPath = normalizePath(path);
        String basePrefix = userProjectPrefix(username, projectName);
        ensureFolderMarker(basePrefix + (normalizedPath.isEmpty() ? "" : normalizedPath + "/"));

        List<String> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String key = basePrefix + (normalizedPath.isEmpty() ? "" : normalizedPath + "/") + file.getOriginalFilename();
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(Optional.ofNullable(file.getContentType()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE))
                    .build();
            s3Client.putObject(put, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            uploaded.add(key);
        }
        return uploaded;
    }

    public ResponseBytes<?> downloadFile(String username, String projectName, String path) {
        String normalized = normalizePath(Objects.requireNonNull(path, "path is required"));
        String key = userProjectPrefix(username, projectName) + normalized;

        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        return s3Client.getObjectAsBytes(get);
    }

    public HeadObjectResponse headFile(String username, String projectName, String path) {
        String normalized = normalizePath(Objects.requireNonNull(path, "path is required"));
        String key = userProjectPrefix(username, projectName) + normalized;
        HeadObjectRequest req = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        return s3Client.headObject(req);
    }

    public long delete(String username, String projectName, String path) {
        String basePrefix = userProjectPrefix(username, projectName);
        String normalized = normalizePath(path);

        if (!StringUtils.hasText(normalized)) {
            return deleteByPrefix(basePrefix);
        }

        String possibleFileKey = basePrefix + normalized;
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(possibleFileKey).build());
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(possibleFileKey).build());
            return 1;
        } catch (NoSuchKeyException e) {
            // Treat as folder
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                // Treat as folder
            } else {
                throw e;
            }
        }
        String folderPrefix = basePrefix + ensureTrailingSlash(normalized);
        return deleteByPrefix(folderPrefix);
    }

    private long deleteByPrefix(String prefix) {
        long totalDeleted = 0;
        String continuation = null;
        do {
            ListObjectsV2Response list = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .continuationToken(continuation)
                    .build());

            List<ObjectIdentifier> toDelete = list.contents().stream()
                    .map(S3Object::key)
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .collect(Collectors.toList());

            if (!toDelete.isEmpty()) {
                DeleteObjectsRequest delReq = DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(toDelete).build())
                        .build();
                totalDeleted += s3Client.deleteObjects(delReq).deleted().size();
            }

            continuation = list.isTruncated() ? list.nextContinuationToken() : null;
        } while (continuation != null);
        return totalDeleted;
    }

    private FileItem toFileItem(String basePrefix, String normalizedPath, S3Object obj) {
        String key = obj.key();
        if (key.endsWith("/")) {
            return null; // folder marker, skip
        }
        String relative = key.substring(basePrefix.length());
        String name = relative;
        if (StringUtils.hasText(normalizedPath)) {
            if (!relative.startsWith(normalizedPath + "/")) {
                return null; // outside this path (shouldn't happen with prefix)
            }
            name = relative.substring(normalizedPath.length() + 1);
        }
        if (name.contains("/")) {
            return null; // not in immediate level (should be filtered by delimiter)
        }
        return new FileItem(name, concatPath(normalizedPath, name), obj.size(), obj.lastModified());
    }

    private void ensureFolderMarker(String prefix) {
        String key = ensureTrailingSlash(prefix);
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(), RequestBody.fromBytes(new byte[0]));
        } catch (S3Exception e) {
            // Ignore if bucket does not support zero-byte folder markers? Generally fine.
            if (e.statusCode() != 409) {
                throw e;
            }
        }
    }

    private String userProjectPrefix(String username, String projectName) {
        return ensureTrailingSlash(username) + ensureTrailingSlash(projectName);
    }

    private String ensureTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s : s + "/";
    }

    private String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) return "";
        String p = path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private String concatPath(String base, String name) {
        if (!StringUtils.hasText(base)) return name;
        return base + "/" + name;
    }

    public String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        return "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded;
    }
}