package com.mesh.user.UserService.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class S3UserStorageService {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    // Create a project folder (zero-byte object with trailing slash)
    public void createProjectFolder(String username, String projectName) {
        String folderKey = username + "/" + projectName + "/";
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(folderKey)
                        .maxKeys(1)
                        .build());
        if (listResponse.keyCount() == 0) {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(folderKey)
                            .build(),
                    RequestBody.empty());
        }
    }

    // List all projects (top-level folders) for a user
    public List<String> listUserProjects(String username) {
        String prefix = username + "/";
        ListObjectsV2Response response = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .delimiter("/")  // delimiter for top-level folders only
                        .build());

        return response.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .map(folderKey -> {
                    String withoutUserPrefix = folderKey.substring(prefix.length());
                    if (withoutUserPrefix.endsWith("/")) {
                        withoutUserPrefix = withoutUserPrefix.substring(0, withoutUserPrefix.length() - 1);
                    }
                    return withoutUserPrefix;
                })
                .collect(Collectors.toList());
    }

    // Delete entire project folder and contents
    public void deleteProject(String username, String projectName) {
        String prefix = username + "/" + projectName + "/";
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .build());
        List<ObjectIdentifier> toDelete = listResponse.contents().stream()
                .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                .collect(Collectors.toList());
        if (!toDelete.isEmpty()) {
            DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(toDelete).build())
                    .build();
            s3Client.deleteObjects(deleteRequest);
        }
    }

    // Upload file to a project folder or nested folder path (folder can be null or empty)
    public void uploadFile(String username, String projectName, String folder, MultipartFile file) throws IOException {
        String key = username + "/" + projectName + "/";
        if (folder != null && !folder.isEmpty()) {
            if (!folder.endsWith("/")) folder += "/";
            key += folder;
        }
        key += file.getOriginalFilename();

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build(),
                RequestBody.fromBytes(file.getBytes()));
    }

    // List files and folders inside any project folder or nested folder path
    public List<String> listProjectFiles(String username, String projectName, String folder) {

        String prefix = username + "/" + projectName + "/";
        if (folder != null && !folder.isEmpty()) {
            if (!folder.endsWith("/")) folder += "/";
            prefix += folder;
        }

        // Use delimiter to list immediate children (files and folders)
        ListObjectsV2Request.Builder listRequestBuilder = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .delimiter("/");

        ListObjectsV2Response response = s3Client.listObjectsV2(listRequestBuilder.build());
        final String effectivePrefix = prefix;
        // Collect folders (common prefixes)
        List<String> folders = response.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .map(p -> p.substring(effectivePrefix.length()))
                .map(p -> p.endsWith("/") ? p.substring(0, p.length() - 1) : p)
                .collect(Collectors.toList());

        // Collect files
        List<String> files = response.contents().stream()
                // Skip the folder object itself if present (key == prefix)
                .filter(obj -> !obj.key().equals(effectivePrefix))
                .map(S3Object::key)
                .map(key -> key.substring(effectivePrefix.length()))
                .collect(Collectors.toList());

        // Combine folders and files (folders first)
        folders.addAll(files);
        return folders;
    }

    // Download a file from a project folder or nested folder path
    public byte[] downloadFile(String username, String projectName, String folder, String fileName) {
        String key = username + "/" + projectName + "/";
        if (folder != null && !folder.isEmpty()) {
            if (!folder.endsWith("/")) folder += "/";
            key += folder;
        }
        key += fileName;

        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build(),
                software.amazon.awssdk.core.sync.ResponseTransformer.toBytes());

        return objectBytes.asByteArray();
    }

    // Delete a file from a project or nested folder
    public void deleteFile(String username, String projectName, String folder, String fileName) {
        String key = username + "/" + projectName + "/";
        if (folder != null && !folder.isEmpty()) {
            if (!folder.endsWith("/")) folder += "/";
            key += folder;
        }
        key += fileName;

        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
    }
}
