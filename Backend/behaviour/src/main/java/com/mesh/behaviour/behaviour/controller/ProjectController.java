package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.dto.EnsureProjectRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;

    @PostMapping("/ensure")
    public Project ensureProject(@RequestBody EnsureProjectRequest req) {
        return projectService.ensure(req);
    }

    // Optional: fetch full project details
    @GetMapping("/{username}/{projectName}")
    public Project getProject(
            @PathVariable String username,
            @PathVariable String projectName) {
        return projectRepository.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
    }
}
