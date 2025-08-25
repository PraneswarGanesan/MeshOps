package com.mesh.user.UserService.Controller;

import com.mesh.user.UserService.Service.S3UserStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/user-storage")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class S3UserStorageController {

    private final S3UserStorageService storageService;

    // Create project folder
    @PostMapping("/{username}/projects/{projectName}")
    public ResponseEntity<String> createProject(
            @PathVariable String username,
            @PathVariable String projectName) {
        storageService.createProjectFolder(username, projectName);
        return ResponseEntity.ok("Project created successfully");
    }

    // List all projects (top-level folders) for a user
    @GetMapping("/{username}/projects")
    public ResponseEntity<List<String>> listProjects(@PathVariable String username) {
        return ResponseEntity.ok(storageService.listUserProjects(username));
    }

    // Delete entire project and all files
    @DeleteMapping("/{username}/projects/{projectName}")
    public ResponseEntity<String> deleteProject(
            @PathVariable String username,
            @PathVariable String projectName) {
        storageService.deleteProject(username, projectName);
        return ResponseEntity.ok("Project deleted successfully");
    }

    // Upload file to project folder or nested folder
    @PostMapping("/{username}/projects/{projectName}/upload")
    public ResponseEntity<String> uploadFile(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestParam("file") MultipartFile file) throws IOException {
        storageService.uploadFile(username, projectName, folder, file);
        return ResponseEntity.ok("File uploaded successfully");
    }

    // List files and folders inside project or nested folder
    @GetMapping("/{username}/projects/{projectName}/files")
    public ResponseEntity<List<String>> listFiles(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestParam(value = "folder", required = false) String folder) {
        return ResponseEntity.ok(storageService.listProjectFiles(username, projectName, folder));
    }

    // Download file from project or nested folder
    @GetMapping("/{username}/projects/{projectName}/download/{fileName}")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String fileName,
            @RequestParam(value = "folder", required = false) String folder) {
        return ResponseEntity.ok(storageService.downloadFile(username, projectName, folder, fileName));
    }

    // Delete file from project or nested folder
    @DeleteMapping("/{username}/projects/{projectName}/delete/{fileName}")
    public ResponseEntity<String> deleteFile(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String fileName,
            @RequestParam(value = "folder", required = false) String folder) {
        storageService.deleteFile(username, projectName, folder, fileName);
        return ResponseEntity.ok("File deleted successfully");
    }
}
