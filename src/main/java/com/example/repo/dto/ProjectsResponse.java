package com.example.repo.dto;

import java.util.List;

public class ProjectsResponse {
    private String username;
    private List<String> projects;

    public ProjectsResponse() {}

    public ProjectsResponse(String username, List<String> projects) {
        this.username = username;
        this.projects = projects;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public List<String> getProjects() {
        return projects;
    }

    public void setProjects(List<String> projects) {
        this.projects = projects;
    }
}