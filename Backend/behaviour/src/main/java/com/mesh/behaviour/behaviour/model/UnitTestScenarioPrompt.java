// src/main/java/com/mesh/behaviour/behaviour/model/UnitTestScenarioPrompt.java
package com.mesh.behaviour.behaviour.model;

import jakarta.persistence.*;
import lombok.*;

import java.sql.Timestamp;

@Entity
@Table(name = "unit_test_scenario_prompts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitTestScenarioPrompt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String projectName;
    private String versionLabel;
    private String message;
    private Long runId;
    private Timestamp createdAt;
}
