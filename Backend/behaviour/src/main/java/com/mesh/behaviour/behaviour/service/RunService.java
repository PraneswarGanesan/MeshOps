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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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

    @Transactional
    public RunStatusView startRun(StartRunRequest req) {
        Project project = projects.findByUsernameAndProjectName(req.getUsername(), req.getProjectName())
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (!Boolean.TRUE.equals(project.getApproved())) {
            throw new IllegalStateException("Project not approved");
        }

        // ensure EC2 instance
        String instanceId = ensureUserInstance(project.getUsername(), project.getProjectName());

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

        /* ===================== FIXED S3 PREFIX LOGIC ===================== */
        String prefix = project.getS3Prefix();
        String bucket = props.getAwsS3Bucket().trim();

        if (prefix.startsWith("s3://")) {
            String expected = "s3://" + bucket + "/";
            if (prefix.startsWith(expected)) {
                prefix = prefix.substring(expected.length());
            } else {
                prefix = prefix.replaceFirst("^s3://[^/]+/", "");
            }
        }
        String projectRoot = prefix.replaceAll("^/+", "").replaceAll("/+$", "");

        // version folder = {username}/{project}/artifacts/versions/vN/
        String versionPrefix = chooseVersionPrefix(projectRoot, req.getVersionName());
        log.info("Selected versionPrefix='{}' for run {}", versionPrefix, run.getId());

        String artifactsPrefix;
        if ("unit".equalsIgnoreCase(req.getTask())) {
            artifactsPrefix = versionPrefix + "unit/run_" + run.getId();
        } else {
            artifactsPrefix = versionPrefix + "behaviour/run_" + run.getId();
        }

        // ✅ Runner key - ensure no leading slash, no spaces
        String runnerKey = "code/meshops/runner.py".replaceAll("^/+", "").trim();
        String s3Runner = "s3://" + bucket + "/" + runnerKey;

        // ✅ Preprocessed prefix - safe
        String preprocPrefix = projectRoot.replaceAll("^/+", "").replaceAll("/+$", "") + "/pre-processed/";
        String s3Preproc = "s3://" + bucket + "/" + preprocPrefix;

        String remoteBase = "s3://" + bucket + "/" + versionPrefix;
        String remoteOut = "s3://" + bucket + "/" + artifactsPrefix;

        List<String> commands = new ArrayList<>();
        commands.add("set -eux");
        commands.add("while [ ! -f /var/lib/cloud/instance/boot-finished ]; do echo Waiting for cloud-init...; sleep 5; done");
        commands.add("pip3 install --quiet --upgrade pip");
        commands.add("pip3 install --quiet --no-cache-dir boto3 numpy pandas scikit-learn pyyaml pillow matplotlib");
        commands.add("echo \"=== Executing runner for run_id=" + run.getId() + " (task=" + req.getTask() + ") ===\"");
        commands.add("pip3 cache purge || true");

        // ✅ Force cleanup before each run
        commands.add("rm -rf /tmp/project_version/* || true");
        commands.add("rm -f /opt/automesh/runner.py || true");
        // ✅ Always pull fresh runner.py
        commands.add("aws s3 cp \"" + s3Runner + "\" /opt/automesh/runner.py");
        commands.add("chmod +x /opt/automesh/runner.py");

        // ✅ Always pull fresh driver + tests.yaml + model (if exists)
        commands.add("aws s3 cp \"" + remoteBase + "driver.py\" /tmp/project_version/driver.py || true");
        commands.add("aws s3 cp \"" + remoteBase + "tests.yaml\" /tmp/project_version/tests.yaml || true");
        commands.add("aws s3 cp \"" + remoteBase + "model.pt\" /tmp/project_version/model.pt || true");
        commands.add("aws s3 cp \"" + remoteBase + "model.pkl\" /tmp/project_version/model.pkl || true");

        // ✅ Preprocessed data sync (always overwrite)
        commands.add("aws s3 cp \"" + s3Preproc + "\" /tmp/project_version/pre-processed/ --recursive || true");

        // ✅ Optional requirements
        commands.add("if aws s3 ls \"" + s3Preproc + "requirements.txt\" >/dev/null 2>&1; then "
                + "aws s3 cp \"" + s3Preproc + "requirements.txt\" /tmp/requirements.txt && "
                + "pip3 install --quiet --no-cache-dir -r /tmp/requirements.txt && pip3 cache purge || true; fi");

        // ✅ Extra installs for image/text workloads
        commands.add("if aws s3 ls \"" + s3Preproc + "images/\" >/dev/null 2>&1; then "
                + "pip3 install --quiet --no-cache-dir torch torchvision pillow && pip3 cache purge || true; fi");

        commands.add("if aws s3 ls \"" + s3Preproc + "texts/\" >/dev/null 2>&1; then "
                + "pip3 install --quiet --no-cache-dir transformers tokenizers && pip3 cache purge || true; fi");

        commands.add("python3 -c \"import boto3, numpy, pandas, sklearn, yaml; print('Baseline imports OK')\"");

        // ✅ Execute runner
        commands.add(String.format(
                "python3 /opt/automesh/runner.py --base_s3 \"%s\" --out_s3 \"%s\" --task %s --run_id %d",
                remoteBase, remoteOut, req.getTask(), run.getId()));

        var send = ssm.sendCommand(SendCommandRequest.builder()
                .documentName("AWS-RunShellScript")
                .instanceIds(instanceId)
                .parameters(Map.of("commands", commands))
                .timeoutSeconds(1800) // 30 minutes timeout
                .build());

        run.setArtifactsPrefix(artifactsPrefix);
        run.setCommandId(send.command().commandId());
        run.setVersionName(versionPrefix);
        runs.save(run);

        RunStatusView view = toView(run);
        writeStatusToS3(view);
        return view;
    }




    /* ===================== Public API Methods ===================== */

    @Transactional(readOnly = true)
    public RunStatusView getStatus(Long runId) {
        return runs.findById(runId)
                .map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    // At top of RunService
// ✅ Runtime-only log store (no DB, cleared on restart)
    private final Map<Long, StringBuilder> liveLogs = new HashMap<>();

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

            String status = resp.statusDetails();
            String out = Optional.ofNullable(resp.standardOutputContent()).orElse("").trim();
            String err = Optional.ofNullable(resp.standardErrorContent()).orElse("").trim();

            // ✅ Maintain incremental logs in memory
            liveLogs.putIfAbsent(runId, new StringBuilder());
            StringBuilder buf = liveLogs.get(runId);

            if (!out.isEmpty()) {
                buf.append(out).append("\n");
            }
            if (!err.isEmpty()) {
                buf.append(err).append("\n");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[status] ").append(status).append("\n");
            if (buf.length() > 0) {
                sb.append("--- logs ---\n").append(buf);
            } else {
                sb.append("(no output yet)\n");
            }

            // ✅ Once finished, clean memory
            if (!"InProgress".equalsIgnoreCase(status)) {
                liveLogs.remove(runId);
            }

            return sb.toString();

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
            default -> {}
        }

        RunStatusView view = toView(run);
        writeStatusToS3(view);
        return view;
    }

    private void stopInstanceIfIdle(String instanceId) {
        try {
            ec2.stopInstances(StopInstancesRequest.builder().instanceIds(instanceId).build());
            log.info("Stopped EC2 instance {}", instanceId);
        } catch (Exception e) {
            log.warn("Failed to stop EC2 instance {}: {}", instanceId, e.getMessage());
        }
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

    private String chooseVersionPrefix(String projectRoot, String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            String normalized = requestedVersion.replaceAll("^/+", "").replaceAll("/+$", "");
            String candidate = S3KeyUtil.join(projectRoot, "artifacts/versions", normalized) + "/";
            if (s3.existsPrefix(candidate)) return candidate;
            throw new IllegalArgumentException("Requested version not found: " + requestedVersion);
        }
        String versionsBase = S3KeyUtil.join(projectRoot, "artifacts/versions") + "/";
        List<String> keys = Collections.emptyList();
        try { keys = s3.listKeys(versionsBase); } catch (Exception ignored) {}
        int max = -1; String best = null;
        Pattern p = Pattern.compile("^v(\\d+)$");
        for (String k : keys) {
            String rest = k.startsWith(versionsBase) ? k.substring(versionsBase.length()) : k;
            String[] parts = rest.split("/");
            if (parts.length > 0) {
                Matcher m = p.matcher(parts[0]);
                if (m.find()) {
                    int n = Integer.parseInt(m.group(1));
                    if (n > max) { max = n; best = parts[0]; }
                }
            }
        }
        if (best != null) return S3KeyUtil.join(versionsBase, best) + "/";
        return projectRoot + "/";
    }

    /* ===================== EC2 helpers ===================== */

    private String ensureUserInstance(String username, String projectName) {
        var res = ec2.describeInstances(DescribeInstancesRequest.builder()
                .filters(
                        Filter.builder().name("tag:automesh-owner").values(username).build(),
                        Filter.builder().name("instance-state-name").values("running", "stopped").build()
                ).build());

        String instanceId = res.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .findFirst().map(Instance::instanceId).orElse(null);

        if (instanceId == null) instanceId = launchInstance(username, projectName);
        else {
            String state = res.reservations().get(0).instances().get(0).state().nameAsString();
            if ("stopped".equalsIgnoreCase(state)) {
                ec2.startInstances(StartInstancesRequest.builder().instanceIds(instanceId).build());
            }
        }

        // Wait for instance to be ready
        ec2.waiter().waitUntilInstanceRunning(DescribeInstancesRequest.builder().instanceIds(instanceId).build());
        ec2.waiter().waitUntilInstanceStatusOk(DescribeInstanceStatusRequest.builder().instanceIds(instanceId).build());

        // Additional wait for SSM agent to be ready
        waitForSSMAgent(instanceId);

        return instanceId;
    }

    private String launchInstance(String username, String projectName) {
        String userData = Base64.getEncoder().encodeToString(
                ubuntuUserData(username, projectName).getBytes(StandardCharsets.UTF_8)
        );

        RunInstancesResponse resp = ec2.runInstances(RunInstancesRequest.builder()
                .imageId(props.getAwsEc2Ami()) // ami-0fc5d935ebf8bc3bc
                .instanceType(InstanceType.fromValue(props.getAwsEc2InstanceType()))
                .minCount(1).maxCount(1)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                        .name(props.getAwsEc2IamInstanceProfile())
                        .build())
                .networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                        .deviceIndex(0)
                        .associatePublicIpAddress(true)
                        .groups(List.of(props.getAwsEc2SecurityGroupIds()))
                        .subnetId(props.getAwsEc2SubnetId())
                        .build())
                .userData(userData)
                .blockDeviceMappings(BlockDeviceMapping.builder()
                        // ✅ FIX: Ubuntu AMIs use /dev/sda1 as root, not /dev/xvda
                        .deviceName("/dev/sda1")
                        .ebs(EbsBlockDevice.builder()
                                .volumeSize(30) // ✅ 30 GB root disk now actually used
                                .volumeType(VolumeType.GP3)
                                .deleteOnTermination(true)
                                .build())
                        .build())
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(
                                Tag.builder().key("automesh-owner").value(username).build(),
                                Tag.builder().key("Name").value(username).build() // ✅ Name = username
                        )
                        .build())
                .build());


        return resp.instances().get(0).instanceId();
    }


    private String ubuntuUserData(String username, String projectName) {
        String bucket = props.getAwsS3Bucket();
        String projectRoot = username + "/" + projectName;
        String requirementsPath = projectRoot + "/pre-processed/requirements.txt";

        return String.format("""
#!/bin/bash
set -eux
exec > >(tee /var/log/user-data.log) 2>&1

echo "Starting user data script for user: %s, project: %s"

# Update and install system packages
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-pip unzip curl snapd

# Install AWS CLI v2 (instead of buggy v1 from apt)
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install --update

# Install SSM agent
snap install amazon-ssm-agent --classic || true
systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true

# Upgrade pip and install baseline Python packages
pip3 install --upgrade pip
echo "Installing baseline Python dependencies..."
pip3 install --no-cache-dir boto3 numpy pandas scikit-learn matplotlib seaborn pyyaml pillow torch torchvision fastapi uvicorn --quiet

# Create directories
mkdir -p /opt/automesh

# Download runner script
aws s3 cp s3://%s/code/meshops/runner.py /opt/automesh/runner.py || true
chmod +x /opt/automesh/runner.py || true

# (Optional) Download generic FastAPI app template
aws s3 cp s3://%s/code/meshops/app.py /opt/automesh/app.py || true

# Check for project-specific requirements
echo "Checking for project-specific requirements at s3://%s/%s"
if aws s3 ls s3://%s/%s; then
    echo "Downloading project-specific requirements..."
    aws s3 cp s3://%s/%s /tmp/project-requirements.txt
    pip3 install --no-cache-dir -r /tmp/project-requirements.txt --quiet
    echo "Project-specific requirements installed"
else
    echo "No project-specific requirements found"
fi

# Verify installation
echo "Verifying Python dependencies..."
python3 -c "import boto3, numpy, pandas, sklearn, matplotlib, yaml, PIL, torch, fastapi, uvicorn; print('All baseline imports successful')"

# Mark cloud-init as complete
echo "User data script completed successfully"
touch /var/lib/cloud/instance/boot-finished

# NOTE: For API serving, you can later start with:
# nohup uvicorn app:app --host 0.0.0.0 --port 8000 > /var/log/fastapi.log 2>&1 &
""",
                username, projectName,
                bucket,   // for runner.py
                bucket,   // for app.py
                bucket, requirementsPath,
                bucket, requirementsPath,
                bucket, requirementsPath);
    }

    private void waitForSSMAgent(String instanceId) {
        int maxAttempts = 30;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                ssm.describeInstanceInformation(DescribeInstanceInformationRequest.builder()
                        .filters(InstanceInformationStringFilter.builder()
                                .key("InstanceIds")
                                .values(instanceId)
                                .build())
                        .build());
                log.info("SSM agent is ready on instance {}", instanceId);
                return;
            } catch (Exception e) {
                if (i == maxAttempts - 1) {
                    log.warn("SSM agent not ready after {} attempts, continuing anyway", maxAttempts);
                    return;
                }
                try {
                    Thread.sleep(10000); // 10 seconds
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
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
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

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