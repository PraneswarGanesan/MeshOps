package com.mesh.unittest.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "unit_test_runs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnitTestRun {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String username;
    
    @Column(nullable = false)
    private String projectName;
    
    @Column(nullable = false)
    private String version;
    
    @Column(nullable = false)
    private String task;
    
    @Enumerated(EnumType.STRING)
    private RunStatus status = RunStatus.PENDING;
    
    private String sandboxId;
    private String commandId;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    private String errorMessage;
    
    // S3 paths
    private String baseS3Path;
    private String outputS3Path;
    
    public enum RunStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
