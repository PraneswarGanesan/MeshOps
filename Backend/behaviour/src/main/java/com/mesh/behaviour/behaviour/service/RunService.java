package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.config.AppProperties;
import com.mesh.behaviour.behaviour.dto.ArtifactView;
import com.mesh.behaviour.behaviour.dto.RunStatusView;
import com.mesh.behaviour.behaviour.dto.StartRunRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.model.Run;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.repository.RunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RunService {

    private final AppProperties props;
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final S3Service s3;
    private final SsmClient ssm;
    private final Ec2Client ec2;
    private final ResultsIngestService resultsIngest;

    @Transactional
    public RunStatusView startRun(StartRunRequest req) {
        Project project = projects.findByUsernameAndProjectName(req.getUsername(), req.getProjectName())
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (!Boolean.TRUE.equals(project.getApproved())) {
            throw new IllegalStateException("Project not approved");
        }

        // ensure instance for this user
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

        // --- Store artifacts under artifacts-behaviour/run_X/ ---
        String artifactsPrefix = project.getUsername() + "/" + project.getProjectName()
                + "/artifacts-behaviour/run_" + run.getId();

        String baseKey = project.getS3Prefix().replace("s3://" + props.getAwsS3Bucket() + "/", "");

        String cmd = String.format(
                "python3 /opt/automesh/runner.py --base_s3 s3://%s/%s --out_s3 s3://%s/%s --task %s --run_id %d",
                props.getAwsS3Bucket(), baseKey, props.getAwsS3Bucket(), artifactsPrefix, req.getTask(), run.getId()
        );

        var send = ssm.sendCommand(SendCommandRequest.builder()
                .documentName("AWS-RunShellScript")
                .instanceIds(instanceId)
                .parameters(Map.of("commands", List.of(cmd)))
                .build());

        run.setArtifactsPrefix(artifactsPrefix);
        run.setCommandId(send.command().commandId());
        runs.save(run);

        return toView(run);
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
            // Command hasn’t started or SSM hasn’t picked it up yet
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
                resultsIngest.ingestRunArtifacts(run);
                stopInstanceIfIdle(run.getInstanceId());
            }
            case CANCELLED, TIMED_OUT, FAILED -> {
                run.setIsRunning(false);
                run.setIsDone(true);
                run.setIsSuccess(false);
                run.setFinishedAt(now());
                stopInstanceIfIdle(run.getInstanceId());
            }
        }
        runs.save(run);
        return toView(run);
    }

    @Transactional(readOnly = true)
    public RunStatusView getStatus(Long runId) {
        Run run = runs.findById(runId).orElseThrow();
        return toView(run);
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

    // ============ EC2 helpers ============

    private String ensureUserInstance(String username) {
        var res = ec2.describeInstances(DescribeInstancesRequest.builder()
                .filters(
                        Filter.builder().name("tag:automesh-owner").values(username).build(),
                        Filter.builder().name("instance-state-name").values("running","stopped").build()
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
                ec2.startInstances(StartInstancesRequest.builder().instanceIds(instanceId).build());
            }
        }

        // Wait until instance is running
        ec2.waiter().waitUntilInstanceRunning(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build()
        );

        // Wait until status checks passed
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
                        .resourceType(ResourceType.INSTANCE)
                        .tags(Tag.builder().key("automesh-owner").value(username).build())
                        .build())
                .build());

        return resp.instances().get(0).instanceId();
    }

    private void stopInstanceIfIdle(String instanceId) {
        ec2.stopInstances(StopInstancesRequest.builder().instanceIds(instanceId).build());
    }

    private String ubuntuUserData() {
        return """
#!/bin/bash
set -eux
apt-get update -y
apt-get install -y snapd python3 python3-pip
snap install amazon-ssm-agent --classic || true
systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true

# Install safe baseline dependencies
pip3 install boto3 scikit-learn matplotlib pandas numpy pyyaml pillow

mkdir -p /opt/automesh
cat > /opt/automesh/runner.py <<'PYEOF'
#!/usr/bin/env python3
import os, sys, subprocess, json, boto3, traceback

def parse_s3(uri):
    uri = uri.replace("s3://", "")
    bucket, key = uri.split("/", 1)
    return bucket, key

def upload_file(s3, local, bucket, key):
    if os.path.exists(local):
        s3.upload_file(local, bucket, key)

def detect_task_from_logs(log_path):
    try:
        with open(log_path) as f:
            text = f.read().lower()
        if "mae:" in text or "rmse:" in text:
            return "regression"
        if "accuracy:" in text or "precision:" in text or "recall:" in text or "f1-score:" in text:
            return "classification"
    except:
        pass
    return "classification"

def majority_baseline_from_tests_csv(tests_csv):
    try:
        import csv
        with open(tests_csv, newline='') as f:
            reader = csv.reader(f)
            headers = next(reader, [])
            hmap = {h.strip().lower(): i for i, h in enumerate(headers)}
            expected_i = hmap.get("expected")
            predicted_i = hmap.get("predicted")
            if expected_i is None or predicted_i is None:
                return None, None, None
            ys, ps = [], []
            for row in reader:
                if len(row) <= max(expected_i, predicted_i): continue
                ys.append(row[expected_i])
                ps.append(row[predicted_i])

            if not ys:
                return None, None, None

            from collections import Counter
            cnt = Counter(ys)
            majority_label, majority_count = max(cnt.items(), key=lambda kv: kv[1])
            baseline_acc = majority_count / float(len(ys))
            unique_preds = len(set(ps))
            return baseline_acc, unique_preds, majority_label
    except Exception:
        return None, None, None

def write_manifest_and_hints(workdir, metrics_path, tests_path, confusion_path, log_path):
    manifest = {}
    task_type = detect_task_from_logs(log_path)
    manifest["task_type"] = task_type
    manifest["pipeline"] = "driver.py -> metrics.json/tests.csv"
    if os.path.exists(metrics_path):
        try:
            with open(metrics_path) as f:
                manifest["metrics"] = json.load(f)
        except:
            pass

    baseline_acc, unique_preds, majority_label = majority_baseline_from_tests_csv(tests_path) if os.path.exists(tests_path) else (None, None, None)
    manifest["majority_baseline_acc"] = baseline_acc
    manifest["unique_preds"] = unique_preds
    manifest["majority_label"] = majority_label

    with open(os.path.join(workdir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    issues = []
    if task_type == "classification":
        if unique_preds is not None and unique_preds < 2:
            issues.append("DEGENERATE_PREDICTIONS")
        if baseline_acc is not None:
            try:
                with open(metrics_path) as f:
                    m = json.load(f)
                acc = m.get("accuracy") or m.get("acc") or m.get("Accuracy")
                if isinstance(acc, (int,float)) and acc <= (baseline_acc + 0.01):
                    issues.append("MAJORITY_BASELINE")
            except:
                pass
        if not os.path.exists(confusion_path):
            issues.append("NO_CONFUSION_MATRIX")

    hints = {
        "issues": issues,
        "notes": {
            "unique_preds": unique_preds,
            "majority_baseline_acc": baseline_acc,
            "majority_label": majority_label
        }
    }
    with open(os.path.join(workdir, "refiner_hints.json"), "w") as f:
        json.dump(hints, f, indent=2)

def main():
    if len(sys.argv) < 9:
        print("Usage: runner.py --base_s3 <s3://...> --out_s3 <s3://...> --task <task> --run_id <id>")
        sys.exit(1)

    args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
    base_s3 = args.get("--base_s3")
    out_s3  = args.get("--out_s3")
    task    = args.get("--task")
    run_id  = args.get("--run_id")

    bucket, base_prefix = parse_s3(base_s3)
    _, out_prefix = parse_s3(out_s3)
    workdir = f"/tmp/run_{run_id}"
    os.makedirs(workdir, exist_ok=True)

    s3 = boto3.client("s3")

    # 1. Download known project files
    files = ["driver.py","tests.yaml","dataset.csv","train.py","predict.py","requirements.txt"]
    for f in files:
        try:
            s3.download_file(bucket, f"{base_prefix}/{f}", os.path.join(workdir,f))
            print(f"Downloaded {f}")
        except Exception:
            pass

    # 2. Install dependencies
    reqs = os.path.join(workdir, "requirements.txt")
    if os.path.exists(reqs):
        print("Installing project requirements...")
        subprocess.run([sys.executable,"-m","pip","install","-r",reqs],check=False)
    else:
        print("No requirements.txt found, using baseline packages already installed.")

    # 3. Run driver.py
    driver = os.path.join(workdir,"driver.py")
    log_path = os.path.join(workdir,"logs.txt")
    try:
        with open(log_path,"w") as logf:
            subprocess.run([sys.executable,driver,"--base_dir",workdir],
                           stdout=logf,stderr=logf,check=False)
    except Exception:
        with open(log_path,"a") as logf:
            traceback.print_exc(file=logf)

    # 4. Ensure metrics.json exists (scrape from logs if needed)
    metrics_path = os.path.join(workdir,"metrics.json")
    if not os.path.exists(metrics_path):
        metrics = {}
        try:
            with open(log_path) as f:
                for line in f:
                    ls = line.strip()
                    for key in ["Accuracy","Precision","Recall","F1-score","MAE","RMSE"]:
                        if ls.startswith(key + ":"):
                            try:
                                val = float(ls.split(":")[1])
                                k = key.lower().replace("-score","")
                                metrics[k] = val
                            except: pass
            if metrics:
                with open(metrics_path,"w") as f: json.dump(metrics,f)
        except: pass

    # 5. Use driver's tests.csv if exists, else fallback to empty schema
    tests_path = os.path.join(workdir,"tests.csv")
    if not os.path.exists(tests_path) or os.path.getsize(tests_path) == 0:
        with open(tests_path, "w") as f:
            f.write("name,category,severity,metric,threshold,expected,predicted,result\\n")
        print("⚠️ Driver did not produce tests.csv — created EMPTY placeholder")
        print("Fallback tests.csv created as EMPTY placeholder (no scenarios)")

    # 6. Derive manifest + refiner hints
    confusion_path = os.path.join(workdir,"confusion_matrix.png")
    write_manifest_and_hints(workdir, metrics_path, tests_path, confusion_path, log_path)

    # 7. Upload artifacts
    for f in ["metrics.json","tests.csv","confusion_matrix.png","logs.txt","manifest.json","refiner_hints.json"]:
        upload_file(s3, os.path.join(workdir,f), bucket, f"{out_prefix}/{f}")

if __name__=="__main__":
    main()
PYEOF

chmod +x /opt/automesh/runner.py
""";
    }

    // ============ helpers ============

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
}