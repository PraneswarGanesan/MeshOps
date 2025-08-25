// src/main/java/com/mesh/behaviour/behaviour/dto/ActivateTestsRequest.java
package com.mesh.behaviour.behaviour.dto;

import lombok.Data;

@Data
public class ActivateTestsRequest {
    private String s3Prefix;  // optional override; else project.s3Prefix
    private String key;       // REQUIRED: bucket-relative key of versioned tests to activate
}
