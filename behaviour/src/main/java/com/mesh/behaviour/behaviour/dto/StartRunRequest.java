package com.mesh.behaviour.behaviour.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRunRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String projectName;

    /**
     * Task name as a string (we're hardcoding allowed values in service, e.g. "classification").
     */
    @NotBlank
    private String task;

    /**
     * Optional: if you already have a running instance, pass it.
     * Otherwise the service may create/ensure one and fill this later.
     */
    private String instanceId;
}
