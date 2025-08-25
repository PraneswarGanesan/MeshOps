package com.mesh.behaviour.behaviour.model;

import jakarta.persistence.*;
import lombok.*;

import java.sql.Timestamp;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String projectName;

    private String task;

    private Boolean isRunning;
    private Boolean isDone;
    private Boolean isSuccess;

    private String instanceId;
    private String commandId;
    private String artifactsPrefix;

    private Timestamp startedAt;
    private Timestamp finishedAt;
}
