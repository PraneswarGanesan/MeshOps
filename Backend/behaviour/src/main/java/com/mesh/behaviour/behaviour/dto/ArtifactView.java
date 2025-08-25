package com.mesh.behaviour.behaviour.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactView {
    /** Suggested friendly name, e.g., "metrics.json", "tests.csv", "confusion_matrix.png". */
    private String name;

    /** S3 key under your bucket, e.g., user/project/artifacts/run_123/metrics.json */
    private String s3Key;

    /** Presigned GET URL to download/view. */
    private String url;

    /** Optional mime if you set it when uploading (application/json, text/csv, image/png, etc.). */
    private String mime;
}
