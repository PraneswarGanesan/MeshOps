package com.example.repo.dto;

import java.util.List;

public class UploadResponse {
    private String username;
    private String projectName;
    private String path;
    private List<String> uploadedKeys;

    public UploadResponse() {}

    public UploadResponse(String username, String projectName, String path, List<String> uploadedKeys) {
        this.username = username;
        this.projectName = projectName;
        this.path = path;
        this.uploadedKeys = uploadedKeys;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getUploadedKeys() {
        return uploadedKeys;
    }

    public void setUploadedKeys(List<String> uploadedKeys) {
        this.uploadedKeys = uploadedKeys;
    }
}