package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.dto.ApprovePlanRequest;
import com.mesh.behaviour.behaviour.dto.GeneratePlanRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final ProjectRepository projects;
    private final S3Service s3;
    private final LlmService llm;

    // STEP 1: Generate with LLM, SAVE to S3 immediately, RETURN driver/tests content + keys to frontend
    @Transactional
    public Mono<Map<String, String>> generateAndSave(String username,
                                                     String projectName,
                                                     GeneratePlanRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = S3KeyUtil.keyOf(project.getS3Prefix());

        // Build context from selected files
        StringBuilder ctx = new StringBuilder();
        ctx.append("### User-selected project files (content included/truncated) ###\n");

        List<String> files = req.getFiles();
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files list is required");
        }

        int maxCharsPerFile = 60_000;
        int csvMaxLines = 150;

        for (String rel : files) {
            String key = S3KeyUtil.join(baseKey, rel);
            if (!s3.exists(key)) {
                ctx.append("\n[missing] ").append(rel).append("\n");
                continue;
            }
            if (isTextLike(rel)) {
                String text = s3.getStringSafe(key, maxCharsPerFile, csvMaxLines);
                ctx.append("\n===== BEGIN FILE: ").append(rel).append(" =====\n")
                        .append(text)
                        .append("\n===== END FILE: ").append(rel).append(" =====\n");
            } else {
                ctx.append("\n[binary or large file omitted] ").append(rel).append("\n");
            }
        }

        // Call Gemini → directly save driver/tests into S3
        return llm.generateAndSaveToS3(req.getBrief(), ctx.toString(), baseKey)
                .map(keys -> {
                    // update project with new keys
                    project.setDriverKey(keys.get("driverKey"));
                    project.setTestsKey(keys.get("testsKey"));
                    project.setApproved(false);
                    projects.save(project);

                    return Map.of(
                            "driverKey", keys.get("driverKey"),
                            "testsKey", keys.get("testsKey"),
                            "driverContent", s3.getStringSafe(keys.get("driverKey"), 100_000, 200),
                            "testsContent", s3.getStringSafe(keys.get("testsKey"), 50_000, 200)
                    );
                });
    }

    // STEP 2: User approves → update project keys and mark approved
    @Transactional
    public Project approvePlan(ApprovePlanRequest req) {
        Project project = projects.findByUsernameAndProjectName(req.getUsername(), req.getProjectName())
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (req.getDriverKey() != null && !req.getDriverKey().isBlank()) {
            project.setDriverKey(req.getDriverKey().trim());
        }
        if (req.getTestsKey() != null && !req.getTestsKey().isBlank()) {
            project.setTestsKey(req.getTestsKey().trim());
        }
        if (req.getS3Prefix() != null && !req.getS3Prefix().isBlank()) {
            project.setS3Prefix(req.getS3Prefix().trim());
        }

        project.setApproved(req.getApproved() != null ? req.getApproved() : false);

        return projects.save(project);
    }

    private boolean isTextLike(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".py") || n.endsWith(".txt") || n.endsWith(".md")
                || n.endsWith(".json") || n.endsWith(".yaml") || n.endsWith(".yml")
                || n.endsWith(".csv") || n.endsWith(".tsv");
    }
}
