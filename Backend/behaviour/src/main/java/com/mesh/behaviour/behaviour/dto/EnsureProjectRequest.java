package com.mesh.behaviour.behaviour.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnsureProjectRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String projectName;

    /**
     * S3 prefix/URI where this project's files live.
     * Example: s3://automesh-ai/<user>/<project>/pre-processed
     */
    @NotBlank
    private String s3Prefix;
}
