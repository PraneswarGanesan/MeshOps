package com.mesh.unittest.dto;

import com.mesh.unittest.model.UnitTestRun;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RunStatusResponse {
    
    private Long runId;
    private String username;
    private String projectName;
    private String version;
    private String task;
    private UnitTestRun.RunStatus status;
    private String sandboxId;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private String baseS3Path;
    private String outputS3Path;
    
    public static RunStatusResponse from(UnitTestRun run) {
        return new RunStatusResponse(
                run.getId(),
                run.getUsername(),
                run.getProjectName(),
                run.getVersion(),
                run.getTask(),
                run.getStatus(),
                run.getSandboxId(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getErrorMessage(),
                run.getBaseS3Path(),
                run.getOutputS3Path()
        );
    }
}
