package com.mesh.behaviour.behaviour.model;

import jakarta.persistence.*;
import lombok.*;

import java.sql.Timestamp;

@Entity
@Table(name = "scenario_prompts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String projectName;

    @Column(length = 4000)
    private String message;   // natural language feedback from user

    private Long runId;       // optional: link to a run
    private Timestamp createdAt;
}
