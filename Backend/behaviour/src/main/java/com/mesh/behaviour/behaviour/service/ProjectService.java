package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.dto.EnsureProjectRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projects;

    @Transactional
    public Project ensure(EnsureProjectRequest req) {
        return projects.findByUsernameAndProjectName(req.getUsername(), req.getProjectName())
                .orElseGet(() -> {
                    Project p = Project.builder()
                            .username(req.getUsername())
                            .projectName(req.getProjectName())
                            .s3Prefix(req.getS3Prefix())
                            .approved(false)
                            .build();
                    return projects.save(p);
                });
    }
}
