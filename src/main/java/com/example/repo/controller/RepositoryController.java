package com.example.repo.controller;

import com.example.repo.dto.CreateProjectRequest;
import com.example.repo.dto.DeleteResponse;
import com.example.repo.dto.ProjectsResponse;
import com.example.repo.dto.TreeResponse;
import com.example.repo.dto.UploadResponse;
import com.example.repo.service.RepositoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/users/{username}")
public class RepositoryController {

    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @PostMapping("/projects")
    public ResponseEntity<Void> createProject(
            @PathVariable("username") @NotBlank String username,
            @Valid @RequestBody CreateProjectRequest request) {
        repositoryService.createProject(username, request.getProjectName());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/projects")
    public ProjectsResponse listProjects(@PathVariable("username") @NotBlank String username) {
        List<String> projects = repositoryService.listProjects(username);
        return new ProjectsResponse(username, projects);
    }

    @GetMapping("/projects/{projectName}/tree")
    public TreeResponse listTree(
            @PathVariable("username") @NotBlank String username,
            @PathVariable("projectName") @NotBlank String projectName,
            @RequestParam(value = "path", required = false) String path) {
        return repositoryService.listTree(username, projectName, path);
    }

    @PostMapping(value = "/projects/{projectName}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @PathVariable("username") @NotBlank String username,
            @PathVariable("projectName") @NotBlank String projectName,
            @RequestParam(value = "path", required = false) String path,
            @RequestPart("files") MultipartFile[] files) throws IOException {
        List<String> keys = repositoryService.uploadFiles(username, projectName, path, Arrays.asList(files));
        UploadResponse response = new UploadResponse(username, projectName, path, keys);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/projects/{projectName}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable("username") @NotBlank String username,
            @PathVariable("projectName") @NotBlank String projectName,
            @RequestParam("path") String path) {
        HeadObjectResponse head = repositoryService.headFile(username, projectName, path);
        ResponseBytes<?> bytes = repositoryService.downloadFile(username, projectName, path);
        String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, repositoryService.contentDisposition(filename))
                .contentType(MediaType.parseMediaType(head.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : head.contentType()))
                .contentLength(head.contentLength())
                .body(bytes.asByteArray());
    }

    @DeleteMapping("/projects/{projectName}")
    public DeleteResponse delete(
            @PathVariable("username") @NotBlank String username,
            @PathVariable("projectName") @NotBlank String projectName,
            @RequestParam(value = "path", required = false) String path) {
        long deleted = repositoryService.delete(username, projectName, path);
        String what = (path == null || path.isBlank()) ? "project" : (path.endsWith("/") ? "folder" : "file/folder");
        return new DeleteResponse("Deleted " + what + " successfully", deleted);
    }
}