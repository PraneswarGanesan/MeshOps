package com.mesh.behaviour.behaviour.dto;

import lombok.Data;

@Data
public class ApprovePlanRequest {
    private String username;
    private String projectName;

    private String driverKey;   // S3 path or relative key
    private String testsKey;    // S3 path or relative key
    private String s3Prefix;    // base S3 prefix for the project

    private Boolean approved;   // true / false
}
