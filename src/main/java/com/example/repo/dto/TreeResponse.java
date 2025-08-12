package com.example.repo.dto;

import java.util.List;

public class TreeResponse {
    private String username;
    private String projectName;
    private String path; // normalized path within the project
    private List<FolderItem> folders;
    private List<FileItem> files;

    public TreeResponse() {}

    public TreeResponse(String username, String projectName, String path, List<FolderItem> folders, List<FileItem> files) {
        this.username = username;
        this.projectName = projectName;
        this.path = path;
        this.folders = folders;
        this.files = files;
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

    public List<FolderItem> getFolders() {
        return folders;
    }

    public void setFolders(List<FolderItem> folders) {
        this.folders = folders;
    }

    public List<FileItem> getFiles() {
        return files;
    }

    public void setFiles(List<FileItem> files) {
        this.files = files;
    }
}