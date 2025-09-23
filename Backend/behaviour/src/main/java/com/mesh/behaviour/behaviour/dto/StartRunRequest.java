package com.mesh.behaviour.behaviour.dto;

import lombok.Data;

/**
 * Request to start a run. versionName is optional; if absent RunService will pick the latest available version
 * under artifacts/versions/.
 */
@Data
public class StartRunRequest {
    private String username;
    private String projectName;
    private String task;
    private String versionName; // optional: e.g. "v0", "v1"
}
