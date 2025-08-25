// src/main/java/com/mesh/behaviour/behaviour/dto/GenerateTestsRequest.java
package com.mesh.behaviour.behaviour.dto;

import lombok.Data;
import java.util.List;

@Data
public class GenerateTestsRequest {
    private String brief;            // what to test now
    private List<String> files;      // context files under s3Prefix (e.g., "train.py")
    private String s3Prefix;         // optional override; else project.s3Prefix
    private String versionLabel;     // optional (e.g., "run_12"); if null uses timestamp
    private Boolean activate;        // optional: if true, also set as canonical tests.yaml
}
