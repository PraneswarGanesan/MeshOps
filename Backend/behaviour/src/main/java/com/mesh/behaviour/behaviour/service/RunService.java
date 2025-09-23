package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.config.AppProperties;
import com.mesh.behaviour.behaviour.dto.ArtifactView;
import com.mesh.behaviour.behaviour.dto.RunStatusView;
import com.mesh.behaviour.behaviour.dto.StartRunRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.model.Run;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.repository.RunRepository;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final AppProperties props;
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final S3Service s3;
    private final SsmClient ssm;
    private final Ec2Client ec2;
    private final ResultsIngestService resultsIngest;
    private final ObjectMapper mapper = new ObjectMapper();

    /* ===================== Run lifecycle ===================== */

    /**
     * Start a run. If StartRunRequest.versionName is provided it will run that version (e.g. "v0", "v1").
     * Otherwise it will pick the latest numeric vX under artifacts/versions/.
     */
    @Transactional
    public RunStatusView startRun(StartRunRequest req) {
        Project project = projects.findByUsernameAndProjectName(req.getUsername(), req.getProjectName())
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (!Boolean.TRUE.equals(project.getApproved())) {
            throw new IllegalStateException("Project not approved");
        }

        // ensure EC2 instance
        String instanceId = ensureUserInstance(project.getUsername());

        Run run = runs.save(Run.builder()
                .username(project.getUsername())
                .projectName(project.getProjectName())
                .task(req.getTask())
                .isRunning(true)
                .isDone(false)
                .isSuccess(false)
                .instanceId(instanceId)
                .artifactsPrefix("")
                .build());

        String artifactsPrefix = project.getUsername() + "/" + project.getProjectName()
                + "/artifacts-behaviour/run_" + run.getId();

        // baseKey is bucket-relative prefix for the project root (no s3://)
        String baseKey = project.getS3Prefix().replaceFirst("^s3://", "")
                .replaceFirst("^" + Pattern.quote(props.getAwsS3Bucket()) + "/", "")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");

        // Choose version prefix (under artifacts/versions/)
        String versionPrefix = chooseVersionPrefix(baseKey, req.getVersionName());
        log.info("Selected versionPrefix='{}' for run {}", versionPrefix, run.getId());

        // form S3 paths and runner command. runner.py expects --base_s3 and --out_s3 (full s3://bucket/key)
        String cmd = String.format(
                "python3 /opt/automesh/runner.py --base_s3 s3://%s/%s --out_s3 s3://%s/%s --task %s --run_id %d",
                props.getAwsS3Bucket(), versionPrefix,
                props.getAwsS3Bucket(), artifactsPrefix,
                req.getTask(), run.getId()
        );

        var send = ssm.sendCommand(SendCommandRequest.builder()
                .documentName("AWS-RunShellScript")
                .instanceIds(instanceId)
                .parameters(Map.of("commands", List.of(cmd)))
                .build());

        run.setArtifactsPrefix(artifactsPrefix);
        run.setCommandId(send.command().commandId());
        run.setVersionName(versionPrefix); // you may want to persist versionName column on Run entity (string)
        runs.save(run);

        RunStatusView view = toView(run);
        writeStatusToS3(view); // sync initial status to S3
        return view;
    }

    @Transactional(readOnly = true)
    public String getConsole(Long runId) {
        Run run = runs.findById(runId).orElseThrow();

        if (run.getCommandId() == null || run.getInstanceId() == null) {
            return "No command/instance available for this run yet.";
        }

        try {
            GetCommandInvocationResponse resp = ssm.getCommandInvocation(
                    GetCommandInvocationRequest.builder()
                            .commandId(run.getCommandId())
                            .instanceId(run.getInstanceId())
                            .build()
            );

            String status = resp.status() == null ? "UNKNOWN" : resp.status().toString();
            String out = Optional.ofNullable(resp.standardOutputContent()).orElse("");
            String err = Optional.ofNullable(resp.standardErrorContent()).orElse("");

            StringBuilder sb = new StringBuilder();
            sb.append("[status] ").append(status).append('\n');
            if (!out.isEmpty()) {
                sb.append("--- stdout ---\n").append(out.trim()).append('\n');
            }
            if (!err.isEmpty()) {
                sb.append("--- stderr ---\n").append(err.trim()).append('\n');
            }
            return sb.toString().isBlank() ? "[status] " + status + "\n(no output yet)" : sb.toString();

        } catch (InvocationDoesNotExistException e) {
            return "Invocation not available yet. Try again in a few seconds.";
        } catch (Exception e) {
            return "Failed to fetch console output: " + e.getMessage();
        }
    }

    @Transactional
    public RunStatusView pollAndUpdate(Long runId) {
        Run run = runs.findById(runId).orElseThrow();
        if (run.getCommandId() == null || run.getInstanceId() == null) return toView(run);

        var inv = ssm.listCommandInvocations(b -> b.commandId(run.getCommandId()).details(true));
        CommandInvocationStatus status = inv.commandInvocations().isEmpty()
                ? CommandInvocationStatus.UNKNOWN_TO_SDK_VERSION
                : inv.commandInvocations().get(0).status();

        switch (status) {
            case IN_PROGRESS -> {
                run.setIsRunning(true);
                run.setIsDone(false);
            }
            case SUCCESS -> {
                run.setIsRunning(false);
                run.setIsDone(true);
                run.setIsSuccess(true);
                run.setFinishedAt(now());
                runs.save(run);
                // ingest artifacts: this should read outputs under run.artifactsPrefix
                try { resultsIngest.ingestRunArtifacts(run); } catch (Exception ex) { log.error("Ingest failed", ex); }
                stopInstanceIfIdle(run.getInstanceId());
            }
            case CANCELLED, TIMED_OUT, FAILED -> {
                run.setIsRunning(false);
                run.setIsDone(true);
                run.setIsSuccess(false);
                run.setFinishedAt(now());
                runs.save(run);
                stopInstanceIfIdle(run.getInstanceId());
            }
            default -> { /* keep current state */ }
        }

        RunStatusView view = toView(run);
        writeStatusToS3(view); // sync to S3
        return view;
    }

    @Transactional(readOnly = true)
    public RunStatusView getStatus(Long runId) {
        Optional<Run> optRun = runs.findById(runId);
        if (optRun.isPresent()) {
            return toView(optRun.get());
        }
        // fallback: try S3
        String prefix = "artifacts-behaviour/run_" + runId;
        String key = prefix + "/status.json";
        if (s3.exists(key)) {
            try {
                String json = s3.getString(key);
                return mapper.readValue(json, RunStatusView.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read status.json from S3", e);
            }
        }
        throw new IllegalArgumentException("Run not found: " + runId);
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> listArtifacts(Long runId) {
        Run run = runs.findById(runId).orElseThrow();
        var urls = s3.listStandardArtifactUrls(run.getArtifactsPrefix(), Duration.ofMinutes(60));

        List<ArtifactView> out = new ArrayList<>();
        urls.forEach((name, url) -> out.add(ArtifactView.builder()
                .name(name)
                .s3Key(run.getArtifactsPrefix() + "/" + name)
                .url(url.toString())
                .mime(guessMime(name))
                .build()));
        return out;
    }

    /* ===================== Version selection helpers ===================== */

    /**
     * Choose a version prefix under: <baseKey>/artifacts/versions/
     * - If requestedVersion provided and exists -> return that prefix (with trailing slash).
     * - Else scan available version folders (v0, v1, v2...) and pick highest numeric vN.
     * - If nothing found, return baseKey (project root) with trailing slash (caller must handle).
     *
     * NOTE: s3.listKeys(prefix) returns object keys under prefix.
     */
    private String chooseVersionPrefix(String baseKey, String requestedVersion) {
        String versionsBase = S3KeyUtil.join(baseKey, "artifacts/versions");
        if (!versionsBase.endsWith("/")) versionsBase = versionsBase + "/";

        if (StringUtils.hasText(requestedVersion)) {
            // normalize requested to not include slashes
            String normalized = requestedVersion.replaceAll("^/+", "").replaceAll("/+$", "");
            String candidate = S3KeyUtil.join(versionsBase, normalized) + "/";
            if (s3.existsPrefix(candidate)) return candidate;
            throw new IllegalArgumentException("Requested version not found: " + requestedVersion);
        }

        // List keys under versionsBase and parse unique immediate folders
        List<String> keys = Collections.emptyList();
        try {
            keys = s3.listKeys(versionsBase);
        } catch (Exception e) {
            log.warn("Failed listing version keys for prefix {}: {}", versionsBase, e.getMessage());
            keys = Collections.emptyList();
        }

        int maxNum = -1;
        String best = null;
        Pattern p = Pattern.compile("^v(\\d+)$");
        for (String k : keys) {
            String rest = k;
            if (rest.startsWith(versionsBase)) rest = rest.substring(versionsBase.length());
            String[] parts = rest.split("/");
            if (parts.length > 0 && StringUtils.hasText(parts[0])) {
                Matcher m = p.matcher(parts[0]);
                if (m.find()) {
                    try {
                        int n = Integer.parseInt(m.group(1));
                        if (n > maxNum) {
                            maxNum = n;
                            best = parts[0];
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (best != null) {
            return S3KeyUtil.join(versionsBase, best) + "/";
        }

        // fallback: no versions found -> use canonical project root
        String fallback = baseKey.endsWith("/") ? baseKey : baseKey + "/";
        log.warn("No versions found under {}; falling back to {}", versionsBase, fallback);
        return fallback;
    }

    /* ===================== EC2 helpers (unchanged) ===================== */

    private String ensureUserInstance(String username) {
        var res = ec2.describeInstances(DescribeInstancesRequest.builder()
                .filters(
                        software.amazon.awssdk.services.ec2.model.Filter.builder().name("tag:automesh-owner").values(username).build(),
                        software.amazon.awssdk.services.ec2.model.Filter.builder().name("instance-state-name").values("running","stopped").build()
                ).build());

        String instanceId = res.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .findFirst()
                .map(Instance::instanceId)
                .orElse(null);

        if (instanceId == null) {
            instanceId = launchInstance(username);
        } else {
            String state = res.reservations().get(0).instances().get(0).state().nameAsString();
            if ("stopped".equalsIgnoreCase(state)) {
                ec2.startInstances(software.amazon.awssdk.services.ec2.model.StartInstancesRequest.builder().instanceIds(instanceId).build());
            }
        }

        ec2.waiter().waitUntilInstanceRunning(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build()
        );
        ec2.waiter().waitUntilInstanceStatusOk(
                DescribeInstanceStatusRequest.builder().instanceIds(instanceId).build()
        );

        return instanceId;
    }

    private String launchInstance(String username) {
        String userData = Base64.getEncoder().encodeToString(ubuntuUserData().getBytes(StandardCharsets.UTF_8));

        RunInstancesResponse resp = ec2.runInstances(RunInstancesRequest.builder()
                .imageId(props.getAwsEc2Ami())
                .instanceType(InstanceType.fromValue(props.getAwsEc2InstanceType()))
                .minCount(1).maxCount(1)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name(props.getAwsEc2IamInstanceProfile()).build())
                .networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                        .deviceIndex(0)
                        .associatePublicIpAddress(true)
                        .groups(List.of(props.getAwsEc2SecurityGroupIds()))
                        .subnetId(props.getAwsEc2SubnetId())
                        .build())
                .userData(userData)
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.INSTANCE)
                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                .key("automesh-owner").value(username).build())
                        .build())
                .build());

        return resp.instances().get(0).instanceId();
    }

    private void stopInstanceIfIdle(String instanceId) {
        ec2.stopInstances(software.amazon.awssdk.services.ec2.model.StopInstancesRequest.builder().instanceIds(instanceId).build());
    }

    private String ubuntuUserData() {
        return """ 
#!/bin/bash
set -eux
apt-get update -y
apt-get install -y snapd python3 python3-pip awscli
snap install amazon-ssm-agent --classic || true
systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true

# Upgrade pip & install heavy ML deps
pip3 install --upgrade pip
pip3 install boto3 scikit-learn matplotlib pandas numpy pyyaml pillow torch torchvision transformers

# Setup Automesh runner
mkdir -p /opt/automesh
aws s3 cp s3://my-users-meshops-bucket/code/meshops/runner.py /opt/automesh/runner.py || true
chmod +x /opt/automesh/runner.py || true

echo "[`date -u +%Y-%m-%dT%H:%M:%SZ`] Automesh behaviour runner bootstrap complete"
""";
    }

    /* ===================== helpers ===================== */

    private RunStatusView toView(Run run) {
        return RunStatusView.builder()
                .runId(run.getId())
                .username(run.getUsername())
                .projectName(run.getProjectName())
                .task(run.getTask())
                .isRunning(run.getIsRunning())
                .isDone(run.getIsDone())
                .isSuccess(run.getIsSuccess())
                .instanceId(run.getInstanceId())
                .commandId(run.getCommandId())
                .artifactsPrefix(run.getArtifactsPrefix())
                .build();
    }

    private Timestamp now() { return new Timestamp(System.currentTimeMillis()); }

    private String guessMime(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv"))  return "text/csv";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".txt"))  return "text/plain";
        return "application/octet-stream";
    }

    /* ===================== S3 sync ===================== */

    private void writeStatusToS3(RunStatusView view) {
        try {
            String key = view.getArtifactsPrefix() + "/status.json";
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(view);
            s3.putString(key, json, "application/json");
        } catch (Exception e) {
            log.error("Failed to write status.json to S3", e);
        }
    }
}
