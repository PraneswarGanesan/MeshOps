package com.mesh.behaviour.behaviour.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactView {
    private String name;
    private String s3Key;
    private String url;
    private String mime;
    private String version;   // optional, extracted from s3Key
}

