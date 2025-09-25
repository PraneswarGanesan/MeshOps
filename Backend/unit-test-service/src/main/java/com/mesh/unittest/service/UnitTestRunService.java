package com.mesh.unittest.service;

import com.mesh.unittest.config.AppProperties;
import com.mesh.unittest.dto.RunStatusResponse;
import com.mesh.unittest.dto.StartRunRequest;
import com.mesh.unittest.model.UnitTestRun;
import com.mesh.unittest.repository.UnitTestRunRepository;
import com.mesh.unittest.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnitTestRunService {

    private final UnitTestRunRepository runRepository;
    private final SsmClient ssmClient;
    private final AppProperties appProperties;
    private final S3Service s3Service;

    public RunStatusResponse startRun(String username, String projectName, String version, StartRunRequest request) {
        // Create run record
        UnitTestRun run = new UnitTestRun();
        run.setUsername(username);
        run.setProjectName(projectName);
        run.setVersion(version);
        run.setTask(request.getTask());
        run.setStatus(UnitTestRun.RunStatus.PENDING);
        
        // Set S3 paths
        String baseS3Path = "s3://" + appProperties.getS3Bucket() + "/" + 
                           S3KeyUtil.buildVersionPath(username, projectName, version);
        String outputS3Path = "s3://" + appProperties.getS3Bucket() + "/" + 
                             S3KeyUtil.buildUnitRunPath(username, projectName, version, null);
        
        run.setBaseS3Path(baseS3Path);
        run = runRepository.save(run);
        
        // Update output path with actual run ID
        outputS3Path = "s3://" + appProperties.getS3Bucket() + "/" + 
                      S3KeyUtil.buildUnitRunPath(username, projectName, version, run.getId());
        run.setOutputS3Path(outputS3Path);
        run = runRepository.save(run);
        
        log.info("Created unit test run {} for {}/{}/{}", run.getId(), username, projectName, version);
        
        // Start async execution
        executeRunAsync(run);
        
        return RunStatusResponse.from(run);
    }

    @Async
    public CompletableFuture<Void> executeRunAsync(UnitTestRun run) {
        try {
            run.setStatus(UnitTestRun.RunStatus.RUNNING);
            run.setStartedAt(LocalDateTime.now());
            runRepository.save(run);
            
            // Execute via SSM
            String commandId = executeRunCommand(run);
            run.setCommandId(commandId);
            runRepository.save(run);
            
            // Monitor execution
            monitorExecution(run);
            
        } catch (Exception e) {
            log.error("Error executing unit test run {}", run.getId(), e);
            run.setStatus(UnitTestRun.RunStatus.FAILED);
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);
        }
        
        return CompletableFuture.completedFuture(null);
    }

    private String executeRunCommand(UnitTestRun run) {
        String userDataScript = buildUserDataScript(run);
        
        try {
            SendCommandRequest request = SendCommandRequest.builder()
                    .documentName("AWS-RunShellScript")
                    .parameters(java.util.Map.of("commands", List.of(userDataScript)))
                    .targets(Target.builder()
                            .key("tag:Name")
                            .values("meshops-sandbox")
                            .build())
                    .timeoutSeconds(3600) // 1 hour timeout
                    .build();

            SendCommandResponse response = ssmClient.sendCommand(request);
            String commandId = response.command().commandId();
            
            log.info("Started SSM command {} for run {}", commandId, run.getId());
            return commandId;
            
        } catch (Exception e) {
            log.error("Failed to execute SSM command for run {}", run.getId(), e);
            throw new RuntimeException("Failed to start unit test execution", e);
        }
    }

    private String buildUserDataScript(UnitTestRun run) {
        return String.format("""
            #!/bin/bash
            set -e
            
            # Wait for cloud-init
            while [ ! -f /var/lib/cloud/instance/boot-finished ]; do
                echo "Waiting for cloud-init..."
                sleep 5
            done
            
            # Update pip and install dependencies
            pip3 install --quiet --upgrade pip
            pip3 install --quiet --no-cache-dir boto3 numpy pandas scikit-learn pyyaml pillow matplotlib seaborn
            pip3 cache purge
            
            # Create directories
            mkdir -p /opt/automesh /tmp/project_version/pre-processed
            
            # Download runner
            aws s3 cp s3://%s/%s /opt/automesh/runner.py
            chmod +x /opt/automesh/runner.py
            
            # Download pre-processed data
            aws s3 cp s3://%s/%s/pre-processed/ /tmp/project_version/pre-processed/ --recursive || true
            
            # Install additional dependencies if needed
            if aws s3 ls s3://%s/%s/pre-processed/requirements.txt; then
                aws s3 cp s3://%s/%s/pre-processed/requirements.txt /tmp/requirements.txt
                pip3 install --quiet --no-cache-dir -r /tmp/requirements.txt || true
            fi
            
            pip3 install --quiet --no-cache-dir torch torchvision transformers tokenizers flask || true
            pip3 cache purge
            
            # Check baseline imports
            python3 -c "import boto3, numpy, pandas, sklearn, yaml; print('Baseline imports OK')"
            
            # Execute runner
            python3 /opt/automesh/runner.py \\
                --base_s3 %s \\
                --out_s3 %s \\
                --task %s \\
                --run_id %d
            """,
            appProperties.getS3Bucket(), appProperties.getRunner().getS3CodePath(),
            appProperties.getS3Bucket(), run.getUsername() + "/" + run.getProjectName(),
            appProperties.getS3Bucket(), run.getUsername() + "/" + run.getProjectName(),
            appProperties.getS3Bucket(), run.getUsername() + "/" + run.getProjectName(),
            run.getBaseS3Path(),
            run.getOutputS3Path(),
            run.getTask(),
            run.getId()
        );
    }

    private void monitorExecution(UnitTestRun run) {
        if (run.getCommandId() == null) return;
        
        try {
            // Poll command status
            boolean completed = false;
            int maxAttempts = 120; // 10 minutes max
            int attempts = 0;
            
            while (!completed && attempts < maxAttempts) {
                Thread.sleep(5000); // Wait 5 seconds
                attempts++;
                
                GetCommandInvocationRequest request = GetCommandInvocationRequest.builder()
                        .commandId(run.getCommandId())
                        .instanceId(getInstanceId())
                        .build();
                
                try {
                    GetCommandInvocationResponse response = ssmClient.getCommandInvocation(request);
                    CommandInvocationStatus status = response.status();
                    
                    if (status == CommandInvocationStatus.SUCCESS) {
                        run.setStatus(UnitTestRun.RunStatus.COMPLETED);
                        completed = true;
                    } else if (status == CommandInvocationStatus.FAILED || 
                              status == CommandInvocationStatus.CANCELLED ||
                              status == CommandInvocationStatus.TIMED_OUT) {
                        run.setStatus(UnitTestRun.RunStatus.FAILED);
                        run.setErrorMessage("Command execution failed: " + status);
                        completed = true;
                    }
                    
                } catch (Exception e) {
                    log.warn("Error checking command status for run {}: {}", run.getId(), e.getMessage());
                }
            }
            
            if (!completed) {
                run.setStatus(UnitTestRun.RunStatus.FAILED);
                run.setErrorMessage("Execution timeout");
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.setStatus(UnitTestRun.RunStatus.FAILED);
            run.setErrorMessage("Execution interrupted");
        } finally {
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);
        }
    }

    private String getInstanceId() {
        // This should be configured or discovered dynamically
        // For now, return a placeholder
        return "i-sandbox-instance";
    }

    public List<RunStatusResponse> getRuns(String username, String projectName, String version) {
        return runRepository.findByUsernameAndProjectNameAndVersionOrderByCreatedAtDesc(username, projectName, version)
                .stream()
                .map(RunStatusResponse::from)
                .toList();
    }

    public Optional<RunStatusResponse> getRun(String username, String projectName, String version, Long runId) {
        return runRepository.findByUsernameAndProjectNameAndVersionAndId(username, projectName, version, runId)
                .map(RunStatusResponse::from);
    }

    public String getRunLogs(String username, String projectName, String version, Long runId) {
        String logsKey = S3KeyUtil.join(
                S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                "logs.txt"
        );
        
        if (s3Service.exists(logsKey)) {
            return s3Service.getString(logsKey);
        }
        
        return "Logs not available yet";
    }

    public String getRunMetrics(String username, String projectName, String version, Long runId) {
        String metricsKey = S3KeyUtil.join(
                S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                "metrics.json"
        );
        
        if (s3Service.exists(metricsKey)) {
            return s3Service.getString(metricsKey);
        }
        
        // Try partial metrics
        String partialMetricsKey = S3KeyUtil.join(
                S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                "partial_metrics.json"
        );
        
        if (s3Service.exists(partialMetricsKey)) {
            return s3Service.getString(partialMetricsKey);
        }
        
        return "{\"status\": \"no_metrics\", \"message\": \"Metrics not available yet\"}";
    }
}
