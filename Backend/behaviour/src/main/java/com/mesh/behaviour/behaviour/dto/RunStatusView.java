package com.mesh.behaviour.behaviour.dto;

import lombok.*;

import java.sql.Timestamp;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunStatusView {
    private Long runId;
    private String username;
    private String projectName;
    private String version;       // NEW
    private String task;
    private Boolean isRunning;
    private Boolean isDone;
    private Boolean isSuccess;
    private String instanceId;
    private String commandId;
    private String artifactsPrefix;
}