package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.dto.EnsureProjectRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping("/ensure")
    public Project ensureProject(@RequestBody EnsureProjectRequest req) {
        return projectService.ensure(req);
    }
}
