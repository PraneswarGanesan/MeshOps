package com.mesh.behaviour.behaviour.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BehaviorTestCaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String projectName;

    private Long runId;

    private String name;
    private String category;
    private String severity;

    private Boolean passed;
    private Boolean skipped;

    private String metric;
    private Double value;
    private Double threshold;
}
