package com.mesh.behaviour.behaviour.model;

import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import jakarta.persistence.*;
import java.sql.Timestamp;

/**
 * Run entity represents a behaviour-run execution record.
 *
 * Matches fields referenced by RunService (instanceId, commandId, artifactsPrefix, isRunning, isDone, isSuccess, finishedAt).
 */
@Entity
@Table(name = "runs", indexes = {
        @Index(name = "idx_runs_username_project", columnList = "username, project_name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(name = "project_name", nullable = false, length = 256)
    private String projectName;

    @Column(length = 128)
    private String task;

    @Column(name = "is_running", nullable = false)
    @ColumnDefault("false")
    private Boolean isRunning = false;

    @Column(name = "is_done", nullable = false)
    @ColumnDefault("false")
    private Boolean isDone = false;

    @Column(name = "is_success", nullable = false)
    @ColumnDefault("false")
    private Boolean isSuccess = false;

    @Column(name = "instance_id", length = 128)
    private String instanceId;

    @Column(name = "command_id", length = 256)
    private String commandId;

    @Column(name = "artifacts_prefix", length = 1024)
    private String artifactsPrefix;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "started_at")
    private Timestamp startedAt;

    @Column(name = "finished_at")
    private Timestamp finishedAt;
    @Column(name = "version_name", length = 128)
    private String versionName;



    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = new Timestamp(System.currentTimeMillis());
        }
        if (this.startedAt == null && Boolean.TRUE.equals(this.isRunning)) {
            this.startedAt = new Timestamp(System.currentTimeMillis());
        }
    }

    @PreUpdate
    protected void onUpdate() {
        // if run marked done and finishedAt still null, stamp it
        if (Boolean.TRUE.equals(this.isDone) && this.finishedAt == null) {
            this.finishedAt = new Timestamp(System.currentTimeMillis());
        }
    }
}
