// src/pages/BehaviourTest.jsx
// Behaviour Testing – review/edit before approve, per-run testcase generation & versioning

import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  ensureProject,
  generatePlan,
  approvePlan,
  startRun,
  getRunStatus,
  pollRun,
  listArtifacts,
  generateTestsNow,
} from "../api/behaviour";
import { StorageAPI } from "../api/storage";

/* ---------- atoms ---------- */
const Btn = ({ className = "", variant = "default", ...p }) => {
  const variants = {
    default: "border-white/15 hover:bg-white/10",
    primary: "border-transparent bg-[#2D6BFF] hover:bg-[#275FE3]",
    ghost: "border-transparent hover:bg-white/5",
    danger: "border-rose-500/40 hover:bg-rose-500/10",
  };
  return (
    <button
      {...p}
      className={
        "px-3 py-2 text-xs rounded transition border " +
        (variants[variant] || variants.default) +
        " " +
        className
      }
    />
  );
};
const Input = (props) => (
  <input
    {...props}
    className={
      (props.className || "") +
      " bg-[#121235] border border-white/10 rounded px-2 py-2 text-sm text-white placeholder-white/40"
    }
  />
);
const TextArea = (props) => (
  <textarea
    {...props}
    className={
      (props.className || "") +
      " bg-[#121235] border border-white/10 rounded p-2 text-sm text-white font-mono placeholder-white/40"
    }
  />
);
const Card = ({ title, action, children }) => (
  <div className="rounded-2xl border border-white/10 bg-[#0B0B2A]">
    <div className="flex items-center justify-between px-4 py-3 border-b border-white/10">
      <div className="text-white/80 font-semibold">{title}</div>
      {action}
    </div>
    <div className="p-4">{children}</div>
  </div>
);
const Badge = ({ tone = "muted", children }) => {
  const cls = {
    green: "bg-emerald-400/10 border-emerald-400/30 text-emerald-300",
    red: "bg-rose-400/10 border-rose-400/30 text-rose-300",
    blue: "bg-sky-400/10 border-sky-400/30 text-sky-300",
    muted: "bg-white/5 border-white/15 text-white/70",
  }[tone];
  return (
    <span className={"px-2 py-0.5 rounded text-[11px] border " + cls}>
      {children}
    </span>
  );
};
const Step = ({ idx, label, state }) => {
  const color =
    state === "done"
      ? "bg-emerald-500"
      : state === "doing"
      ? "bg-sky-500 animate-pulse"
      : state === "fail"
      ? "bg-rose-500"
      : "bg-white/20";
  return (
    <div className="flex items-center gap-3">
      <div
        className={
          "w-6 h-6 rounded-full flex items-center justify-center text-[11px] " +
          color
        }
      >
        {idx}
      </div>
      <div className="text-sm text-white/80">{label}</div>
    </div>
  );
};

/* ---------- utils ---------- */
const BASE_STORAGE = "http://localhost:8081";

function normalizeKey(key) {
  if (!key) return "";
  const m = /^s3:\/\/[^/]+\/(.+)$/.exec(key);
  if (m) return m[1];
  if (key.startsWith("/")) return key.slice(1);
  return key;
}

function parseCSV(text) {
  const rows = [];
  let row = [],
    field = "",
    q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i],
      n = text[i + 1];
    if (q) {
      if (c === '"' && n === '"') {
        field += '"';
        i++;
      } else if (c === '"') {
        q = false;
      } else {
        field += c;
      }
    } else {
      if (c === '"') q = true;
      else if (c === ",") {
        row.push(field);
        field = "";
      } else if (c === "\r") {
      } else if (c === "\n") {
        row.push(field);
        rows.push(row);
        row = [];
        field = "";
      } else field += c;
    }
  }
  if (field !== "" || q || row.length) {
    row.push(field);
    rows.push(row);
  }
  if (!rows.length) return { headers: [], rows: [] };
  const headers = rows[0];
  const data = rows
    .slice(1)
    .filter((r) => r.length && !(r.length === 1 && r[0] === ""));
  return { headers, rows: data };
}

function splitKeyForStorage(username, projectName, s3Key) {
  const prefix = `${username}/${projectName}/`;
  if (!s3Key || !s3Key.startsWith(prefix)) return null;
  const rel = s3Key.slice(prefix.length);
  const parts = rel.split("/").filter(Boolean);
  if (!parts.length) return null;
  const fileName = parts.pop();
  const folder = parts.join("/");
  return { folder, fileName };
}

async function fetchTextWithFallback(artifact, username, projectName) {
  try {
    const r = await fetch(artifact.url, { method: "GET" });
    return await r.text();
  } catch {
    const split = splitKeyForStorage(username, projectName, artifact.s3Key);
    if (!split) throw new Error("No s3Key split");
    const url = `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(
      username
    )}/projects/${encodeURIComponent(
      projectName
    )}/download/${encodeURIComponent(split.fileName)}?folder=${encodeURIComponent(
      split.folder
    )}`;
    const r2 = await fetch(url, { method: "GET" });
    if (!r2.ok) throw new Error(`Storage fallback ${r2.status}`);
    return await r2.text();
  }
}

function splitKey(key) {
  const cleaned = normalizeKey(key);
  const parts = (cleaned || "").split("/").filter(Boolean);
  const fileName = parts.pop() || "";
  const folder = parts.join("/");
  return { folder, fileName };
}
async function downloadS3Text(username, projectName, key) {
  const { folder, fileName } = splitKey(key);
  return await StorageAPI.fetchTextFile(username, projectName, fileName, folder);
}
async function uploadS3Text(
  username,
  projectName,
  key,
  content,
  contentType = "text/plain"
) {
  const { folder, fileName } = splitKey(key);
  return await StorageAPI.uploadTextFile(
    username,
    projectName,
    fileName,
    content,
    folder,
    contentType
  );
}

/* ---------- main ---------- */
export default function BehaviourTest() {
  const username =
    localStorage.getItem("username") ||
    localStorage.getItem("email") ||
    "pg";

  const [projects, setProjects] = useState([]);
  const [projectName, setProjectName] = useState(
    localStorage.getItem("activeProject") || ""
  );
  const [s3Prefix, setS3Prefix] = useState("");
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [brief, setBrief] = useState(
    "Create behaviour tests for my classification model."
  );
  const [filesText, setFilesText] = useState(
    "train.py\npredict.py\ndata/dataset.csv"
  );
  const filesArray = useMemo(
    () => filesText.split(/\r?\n/).map((v) => v.trim()).filter(Boolean),
    [filesText]
  );
  const [driverKey, setDriverKey] = useState("");
  const [testsKey, setTestsKey] = useState("");
  const [approved, setApproved] = useState(true);

  const [stepState, setStepState] = useState({
    ensure: "idle",
    plan: "idle",
    approve: "idle",
    start: "idle",
  });
  const [ctaBusy, setCtaBusy] = useState(false);
  const [runId, setRunId] = useState(null);
  const [status, setStatus] = useState(null);
  const [artifacts, setArtifacts] = useState([]);
  const pollTimer = useRef(null);
  const [metrics, setMetrics] = useState(null);
  const [confusionURL, setConfusionURL] = useState("");
  const [csvInfo, setCsvInfo] = useState({ headers: [], rows: [] });
  const [csvSearch, setCsvSearch] = useState("");
  const [csvPage, setCsvPage] = useState(1);
  const [recentRuns, setRecentRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const runsRefreshTimer = useRef(null);
  const [log, setLog] = useState("");
  const logLine = (s) => setLog((prev) => (prev ? prev + "\n" : "") + s);
  const [showApprovePanel, setShowApprovePanel] = useState(false);
  const [driverText, setDriverText] = useState("");
  const [testsText, setTestsText] = useState("");
  const [saveBusy, setSaveBusy] = useState(false);
  const [genBusy, setGenBusy] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const url = `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(
          username
        )}/projects`;
        const res = await fetch(url);
        const list = (await res.json()) || [];
        setProjects(list);
        if (!projectName && list.length) {
          setProjectName(list[0]);
          localStorage.setItem("activeProject", list[0]);
        }
      } catch {}
    })();
  }, [username]);

  useEffect(() => {
    const auto = `s3://my-users-meshops-bucket/${username}/${
      projectName || "project"
    }/pre-processed`;
    setS3Prefix(auto);
  }, [projectName, username]);

  const refreshRunsFromStorage = async () => {
    if (!projectName) return;
    try {
      const items = await StorageAPI.listFiles(
        username,
        projectName,
        "artifacts-behaviour"
      );
      const runs = items
        .map((n) => {
          const m = /^run_(\d+)\/?$/.exec(String(n).trim());
          return m ? { runId: Number(m[1]) } : null;
        })
        .filter(Boolean)
        .sort((a, b) => b.runId - a.runId);
      setRecentRuns(runs);
      if (!selectedRunId && runs.length) setSelectedRunId(runs[0].runId);
    } catch {}
  };

  useEffect(() => {
    clearInterval(runsRefreshTimer.current || undefined);
    if (!projectName) return;
    refreshRunsFromStorage();
    runsRefreshTimer.current = setInterval(refreshRunsFromStorage, 10000);
    return () => clearInterval(runsRefreshTimer.current || undefined);
  }, [projectName]);

  const CANON_DRIVER = "pre-processed/driver.py";
  const CANON_TESTS = "pre-processed/tests.yaml";

  const runAll = async () => {
    if (!projectName) {
      logLine("Pick or create a project in Storage first.");
      return;
    }

    setCtaBusy(true);
    setArtifacts([]);
    setMetrics(null);
    setConfusionURL("");
    setCsvInfo({ headers: [], rows: [] });
    setSelectedRunId(null);
    setStepState({
      ensure: "doing",
      plan: "idle",
      approve: "idle",
      start: "idle",
    });

    try {
      await ensureProject({ username, projectName, s3Prefix });
      setStepState((s) => ({ ...s, ensure: "done", plan: "doing" }));

      let hasDriver = false,
        hasTests = false;
      try {
        const items = await StorageAPI.listFiles(
          username,
          projectName,
          "pre-processed"
        );
        const names = (items || []).map((n) => String(n).replace(/\/$/, ""));
        hasDriver = names.includes("driver.py");
        hasTests = names.includes("tests.yaml");
        logLine(`S3 check: driver=${hasDriver}, tests=${hasTests}`);
      } catch {
        logLine("Could not list pre-processed; assuming missing.");
      }

      if (hasDriver || hasTests) {
        if (hasDriver) {
          try {
            const dt = await downloadS3Text(username, projectName, CANON_DRIVER);
            setDriverText(dt);
            setDriverKey(CANON_DRIVER);
          } catch {}
        } else setDriverKey(CANON_DRIVER);

        if (hasTests) {
          try {
            const tt = await downloadS3Text(username, projectName, CANON_TESTS);
            setTestsText(tt);
            setTestsKey(CANON_TESTS);
          } catch {}
        } else setTestsKey(CANON_TESTS);

        setStepState((s) => ({ ...s, plan: "done", approve: "doing" }));
        setShowApprovePanel(true);
        setCtaBusy(false);
        return;
      }

      logLine("⚠️ No driver/tests found → generating via LLM…");
      const gen = await generatePlan(username, projectName, {
        brief,
        files: filesArray,
      });
      const m = (gen && gen.data) || {};
      const guessDriver = normalizeKey(m.driverKey || CANON_DRIVER);
      const guessTests = normalizeKey(m.testsKey || CANON_TESTS);

      setDriverKey(guessDriver);
      setTestsKey(guessTests);
      let dt = m.driverContent || "";
      if (!dt) {
        try {
          dt = await downloadS3Text(username, projectName, guessDriver);
        } catch {}
      }
      let tt = m.testsContent || "";
      if (!tt) {
        try {
          tt = await downloadS3Text(username, projectName, guessTests);
        } catch {}
      }

      setDriverText(dt);
      setTestsText(tt);
      setStepState((s) => ({ ...s, plan: "done", approve: "doing" }));
      setShowApprovePanel(true);
      setCtaBusy(false);
    } catch (err) {
      logLine("❌ Error: " + (err?.response?.data || err.message));
      setStepState((s) => ({ ...s, plan: "fail" }));
      setCtaBusy(false);
    }
  };

  const generateNewTests = async () => {
    if (!projectName) {
      logLine("Pick or create a project in Storage first.");
      return;
    }
    setGenBusy(true);
    try {
      const res = await generateTestsNow(username, projectName, {
        brief,
        files: filesArray,
        s3Prefix,
      });
      const data = (res && res.data) || {};
      const key = normalizeKey(
        data.versionKey || data.key || data.testsKey || ""
      );
      if (key) {
        setTestsKey(key);
        try {
          const txt = await downloadS3Text(username, projectName, key);
          setTestsText(txt);
          logLine(`Generated tests at: ${key}`);
        } catch (e) {
          logLine(
            `Generated tests key set but download failed: ${e.message}`
          );
        }
      }
    } catch (e) {
      logLine("Generate tests failed: " + (e?.response?.data || e.message));
    } finally {
      setGenBusy(false);
    }
  };

  // ✅ Approve and run
  const approveAndRun = async () => {
  if (!projectName) {
    logLine("Pick or create a project in Storage first.");
    return;
  }
  setSaveBusy(true);
  try {
    // upload current driver.py and tests.yaml to S3
    await uploadS3Text(
      username,
      projectName,
      `pre-processed/driver.py`,
      driverText,
      "text/x-python"
    );
    await uploadS3Text(
      username,
      projectName,
      `pre-processed/tests.yaml`,
      testsText,
      "text/yaml"
    );

    // ✅ FIX: prepend username/projectName when sending approvePlan
    await approvePlan({
      username,
      projectName,
      driverKey: `${username}/${projectName}/pre-processed/driver.py`,
      testsKey: `${username}/${projectName}/pre-processed/tests.yaml`,
      s3Prefix: `s3://my-users-meshops-bucket/${username}/${projectName}/pre-processed`,
      approved: true,
    });

    // start run
    const r = await startRun({ username, projectName, task: "classification" });
    const newRunId = r?.data?.runId;
    setRunId(newRunId);
    setStatus(null);
    setStepState((s) => ({ ...s, approve: "done", start: "doing" }));
    setShowApprovePanel(false);
    logLine(`✅ Run started with id ${newRunId}`);

    kickPolling(newRunId);
  } catch (e) {
    logLine("❌ Approve & Run failed: " + (e?.response?.data || e.message));
    setStepState((s) => ({ ...s, start: "fail" }));
  } finally {
    setSaveBusy(false);
  }
};

  const kickPolling = (id) => {
    clearPolling();
    if (!id) return;
    pollTimer.current = setInterval(async () => {
      try {
        const r = await getRunStatus(id);
        const st = r.data;
        setStatus(st);
        if (st?.isDone) {
          clearPolling();
          const arts = await listArtifacts(id);
          setArtifacts(arts.data || []);
          refreshRunsFromStorage();
        }
      } catch {
        try {
          const r2 = await pollRun(id);
          const st2 = r2.data;
          setStatus(st2);
          if (st2?.isDone) {
            clearPolling();
            const arts2 = await listArtifacts(id);
            setArtifacts(arts2.data || []);
            refreshRunsFromStorage();
          }
        } catch (e2) {
          clearPolling();
          logLine("Polling failed: " + (e2?.response?.data || e2.message));
        }
      }
    }, 2500);
  };
  const clearPolling = () => {
    if (pollTimer.current) clearInterval(pollTimer.current);
    pollTimer.current = null;
  };
  useEffect(() => clearPolling, []);

  const loadRunFromHistory = async (rid) => {
    setSelectedRunId(rid);
    setRunId(rid);
    setStatus(null);
    setArtifacts([]);
    setMetrics(null);
    setCsvInfo({ headers: [], rows: [] });
    setConfusionURL("");
    try {
      const arts = await listArtifacts(rid);
      setArtifacts(arts.data || []);
    } catch {
      logLine("Failed to load artifacts for run #" + rid);
    }
  };

  useEffect(() => {
    if (!artifacts?.length) {
      setMetrics(null);
      setCsvInfo({ headers: [], rows: [] });
      setConfusionURL("");
      return;
    }
    (async () => {
      try {
        const low = (s) => (s || "").toLowerCase();
        const isImg = (a) => low(a.mime || "").startsWith("image/");
        const isJson =
          (a) =>
            low(a.mime || "").includes("json") ||
            (a.name || a.s3Key || "").toLowerCase().endsWith(".json");
        const isCsv =
          (a) =>
            low(a.mime || "").includes("csv") ||
            (a.name || a.s3Key || "").toLowerCase().endsWith(".csv");
        const isTxt =
          (a) =>
            low(a.mime || "").startsWith("text/") ||
            (a.name || a.s3Key || "").toLowerCase().endsWith(".txt");

        const metricsArt = artifacts.find(
          (a) =>
            (low(a.name).includes("metrics") ||
              low(a.s3Key).includes("metrics")) &&
            isJson(a)
        );

        const confArt = artifacts.find(
          (a) =>
            (low(a.name).includes("confusion") ||
              low(a.s3Key).includes("confusion")) &&
            isImg(a)
        );

        const csvArt =
          artifacts.find(
            (a) =>
              (/(test|result)/.test(low(a.name)) ||
                /(test|result)/.test(low(a.s3Key))) &&
              isCsv(a)
          ) || artifacts.find(isCsv);

        const logsArt = artifacts.find(
          (a) =>
            (/(log|logs)/.test(low(a.name)) ||
              /(log|logs)/.test(low(a.s3Key))) &&
            (isTxt(a) || isCsv(a) || isJson(a))
        );

        if (metricsArt) {
          try {
            const t = await fetchTextWithFallback(
              metricsArt,
              username,
              projectName
            );
            try {
              setMetrics(JSON.parse(t));
            } catch {
              setMetrics(null);
            }
          } catch {
            setMetrics(null);
          }
        } else setMetrics(null);

        if (confArt?.url) {
          setConfusionURL(confArt.url);
        }

        if (csvArt) {
          try {
            const t2 = await fetchTextWithFallback(
              csvArt,
              username,
              projectName
            );
            setCsvInfo(parseCSV(t2));
            setCsvPage(1);
          } catch {
            setCsvInfo({ headers: [], rows: [] });
          }
        } else {
          setCsvInfo({ headers: [], rows: [] });
        }

        if (logsArt) {
          try {
            const t3 = await fetchTextWithFallback(
              logsArt,
              username,
              projectName
            );
            if (t3)
              setLog(
                (prev) =>
                  (prev ? prev + "\n\n" : "") +
                  "Logs:\n" +
                  t3.slice(0, 5000)
              );
          } catch {}
        }
      } catch {}
    })();
  }, [artifacts, selectedRunId, recentRuns, username, projectName]);

  const filteredRows = useMemo(() => {
    if (!csvSearch.trim()) return csvInfo.rows;
    const q = csvSearch.toLowerCase();
    const idxs = csvInfo.headers.map((_, i) => i);
    return csvInfo.rows.filter((r) =>
      idxs.some((i) =>
        (r[i] || "").toString().toLowerCase().includes(q)
      )
    );
  }, [csvInfo, csvSearch]);
  const pageSize = 10;
  const totalPages = Math.max(
    1,
    Math.ceil(filteredRows.length / pageSize)
  );
  const pageRows = filteredRows.slice(
    (csvPage - 1) * pageSize,
    csvPage * pageSize
  );
  const statusIdx = csvInfo.headers.findIndex(
    (h) => h.toLowerCase() === "status"
  );
  const passFail = useMemo(() => {
    if (statusIdx === -1) return null;
    let pass = 0,
      fail = 0,
      other = 0;
    csvInfo.rows.forEach((r) => {
      const v = (r[statusIdx] || "").toLowerCase();
      if (v.includes("pass")) pass++;
      else if (v.includes("fail")) fail++;
      else other++;
    });
    return { pass, fail, other, total: csvInfo.rows.length };
  }, [csvInfo, statusIdx]);

  const statusBadge = (ok, label) => (
    <Badge tone={ok ? "green" : "muted"}>{label}</Badge>
  );

  return (
    <div className="min-h-screen text-white p-4 space-y-4">
      {/* Header */}
      <div className="flex flex-col lg:flex-row lg:items-center gap-3">
        <div className="flex-1">
          <div className="text-[13px] text-white/60">Testing Suite</div>
          <div className="text-2xl font-semibold tracking-wide">
            Behaviour Testing
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-sm text-white/60">Project</span>
          <select
            value={projectName}
            onChange={(e) => {
              setProjectName(e.target.value);
              localStorage.setItem("activeProject", e.target.value);
            }}
            className="bg-[#121235] border border-white/10 rounded px-2 py-2 text-sm"
          >
            {projects.length === 0 && <option value="">—</option>}
            {projects.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>

          <Btn
            variant="primary"
            onClick={runAll}
            disabled={!projectName || ctaBusy}
            className="ml-2"
          >
            {ctaBusy ? "Running…" : "Run Behaviour Tests"}
          </Btn>
        </div>
      </div>

      {/* Overview / Console */}
      <Card
        title={
          <div className="flex items-center gap-3">
            <span>Run Overview</span>
            {runId ? <Badge tone="blue">run #{runId}</Badge> : null}
            {status && (
              <>
                {statusBadge(!!status?.isRunning, "Running")}
                {statusBadge(!!status?.isDone, "Done")}
                <Badge tone={status?.isSuccess ? "green" : "muted"}>
                  Success
                </Badge>
              </>
            )}
          </div>
        }
        action={
          <div className="text-xs text-white/50">
            {projectName ? `Project: ${projectName}` : "Select a project"}
          </div>
        }
      >
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="space-y-3">
            <Step idx={1} label="Ensure project" state={stepState.ensure} />
            <Step idx={2} label="Generate plan (LLM)" state={stepState.plan} />
            <Step idx={3} label="Approve plan" state={stepState.approve} />
            <Step idx={4} label="Start run" state={stepState.start} />
          </div>
          <div className="lg:col-span-2">
            <div className="text-xs uppercase tracking-wider text-white/60 mb-2">
              Console
            </div>
            <pre className="bg-black/40 border border-white/10 rounded p-3 text-xs max-h-[180px] overflow-auto whitespace-pre-wrap">
              {log || "—"}
            </pre>
          </div>
        </div>

        {/* Review & Edit panel */}
        {showApprovePanel && (
          <div className="mt-4 rounded-xl border border-white/10 p-3 bg-white/5">
            <div className="flex items-center justify-between mb-2">
              <div className="text-sm text-white/80">
                Review & Edit before Approve
              </div>
              <div className="flex items-center gap-2">
                <Btn
                  variant="ghost"
                  onClick={() => setShowApprovePanel(false)}
                >
                  Close
                </Btn>
                <Btn onClick={generateNewTests} disabled={genBusy}>
                  {genBusy ? "Generating…" : "Generate New Testcases"}
                </Btn>
                <Btn
                  variant="primary"
                  onClick={approveAndRun}
                  disabled={saveBusy}
                >
                  {saveBusy ? "Saving & Starting…" : "Approve & Run"}
                </Btn>
              </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
              <div>
                <div className="text-xs uppercase tracking-wider text-white/60 mb-1">
                  driver.py{" "}
                  <span className="text-white/40">
                    (
                    {driverKey ||
                      `${username}/${projectName}/pre-processed/driver.py`}
                    )
                  </span>
                </div>
                <TextArea
                  rows={18}
                  value={driverText}
                  onChange={(e) => setDriverText(e.target.value)}
                />
              </div>
              <div>
                <div className="text-xs uppercase tracking-wider text-white/60 mb-1">
                  tests.yaml{" "}
                  <span className="text-white/40">
                    (
                    {testsKey ||
                      `${username}/${projectName}/pre-processed/tests.yaml`}
                    )
                  </span>
                </div>
                <TextArea
                  rows={18}
                  value={testsText}
                  onChange={(e) => setTestsText(e.target.value)}
                />
              </div>
            </div>
            <div className="mt-2 text-[11px] text-white/60">
              Tip: Each time you run, we also save a versioned copy of{" "}
              <code>tests.yaml</code> under{" "}
              <code>/pre-processed/tests/tests_YYYYMMDDHHMM.yaml</code> for
              easy comparison.
            </div>
          </div>
        )}
      </Card>

      {/* Previous Runs */}
      <Card
        title="Previous Runs"
        action={
          <div className="flex items-center gap-2">
            <Btn variant="ghost" onClick={refreshRunsFromStorage}>
              Refresh
            </Btn>
            <div className="text-xs text-white/50">
              {recentRuns.length ? `${recentRuns.length} found` : "—"}
            </div>
          </div>
        }
      >
        {recentRuns.length ? (
          <div className="overflow-auto rounded border border-white/10">
            <table className="min-w-full text-sm">
              <thead className="bg-[#101034] text-white/70">
                <tr>
                  <th className="px-3 py-2 text-left border-b border-white/10">
                    Run
                  </th>
                  <th className="px-3 py-2 text-left border-b border-white/10">
                    Artifacts Folder
                  </th>
                  <th className="px-3 py-2 text-right border-b border-white/10">
                    Action
                  </th>
                </tr>
              </thead>
              <tbody>
                {recentRuns.map((r) => (
                  <tr
                    key={r.runId}
                    className={
                      "odd:bg-white/5 hover:bg-white/10 transition " +
                      (selectedRunId === r.runId ? "bg-white/10" : "")
                    }
                  >
                    <td className="px-3 py-2">#{r.runId}</td>
                    <td className="px-3 py-2">
                      artifacts-behaviour/run_{r.runId}/
                    </td>
                    <td className="px-3 py-2 text-right">
                      <Btn
                        onClick={() => loadRunFromHistory(r.runId)}
                        className="px-2 py-1"
                      >
                        View
                      </Btn>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="text-white/60 text-sm">
            No previous runs in S3 yet. They’ll appear as{" "}
            <code>artifacts-behaviour/run_*/</code>.
          </div>
        )}
      </Card>

      <ResultsSection
        metrics={metrics}
        confusionURL={confusionURL}
        csvInfo={csvInfo}
        csvSearch={csvSearch}
        setCsvSearch={setCsvSearch}
        csvPage={csvPage}
        setCsvPage={setCsvPage}
      />
    </div>
  );
}

/* ---------- Results section ---------- */
function ResultsSection({
  metrics,
  confusionURL,
  csvInfo,
  csvSearch,
  setCsvSearch,
  csvPage,
  setCsvPage,
}) {
  const filteredRows = useMemo(() => {
    if (!csvSearch.trim()) return csvInfo.rows;
    const q = csvSearch.toLowerCase();
    const idxs = csvInfo.headers.map((_, i) => i);
    return csvInfo.rows.filter((r) =>
      idxs.some((i) =>
        (r[i] || "").toString().toLowerCase().includes(q)
      )
    );
  }, [csvInfo, csvSearch]);
  const pageSize = 10;
  const totalPages = Math.max(1, Math.ceil(filteredRows.length / pageSize));
  const pageRows = filteredRows.slice(
    (csvPage - 1) * pageSize,
    csvPage * pageSize
  );
  const statusIdx = csvInfo.headers.findIndex(
    (h) => h.toLowerCase() === "status"
  );
  const passFail = useMemo(() => {
    if (statusIdx === -1) return null;
    let pass = 0,
      fail = 0,
      other = 0;
    csvInfo.rows.forEach((r) => {
      const v = (r[statusIdx] || "").toLowerCase();
      if (v.includes("pass")) pass++;
      else if (v.includes("fail")) fail++;
      else other++;
    });
    return { pass, fail, other, total: csvInfo.rows.length };
  }, [csvInfo, statusIdx]);

  return (
    <Card title="Test Results">
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <div className="space-y-3">
          <div className="text-xs uppercase tracking-wider text-white/60">
            Metrics
          </div>
          {metrics ? (
            <div className="grid grid-cols-2 gap-2">
              {Object.entries(metrics).map(([k, v]) => (
                <div key={k} className="rounded border border-white/10 p-3">
                  <div className="text-[11px] text-white/60">{k}</div>
                  <div className="text-xl">
                    {typeof v === "number" ? v.toFixed(4) : String(v)}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-white/60 text-sm">
              Waiting for metrics.json…
            </div>
          )}
        </div>

        <div className="space-y-3">
          <div className="text-xs uppercase tracking-wider text-white/60">
            Confusion Matrix
          </div>
          {confusionURL ? (
            <div className="bg-black/30 p-2 flex items-center justify-center rounded border border-white/10">
              <img
                src={confusionURL}
                alt="Confusion matrix"
                className="max-h-72 object-contain"
              />
            </div>
          ) : (
            <div className="text-white/60 text-sm">
              No confusion matrix available.
            </div>
          )}
        </div>

        <div className="space-y-3">
          <div className="text-xs uppercase tracking-wider text-white/60">
            Testcase Summary
          </div>
          {csvInfo.headers.length ? (
            passFail ? (
              <div className="grid grid-cols-3 gap-2">
                <StatCard label="Passed" value={passFail.pass} tone="green" />
                <StatCard label="Failed" value={passFail.fail} tone="red" />
                <StatCard label="Other" value={passFail.other} />
                <div className="col-span-3 text-[11px] text-white/60 mt-1">
                  Total rows: {passFail.total}
                </div>
              </div>
            ) : (
              <div className="text-white/60 text-sm">
                CSV found. No <code>status</code> column detected.
              </div>
            )
          ) : (
            <div className="text-white/60 text-sm">
              Waiting for tests/results CSV…
            </div>
          )}
        </div>
      </div>

      <div className="mt-5">
        <div className="flex items-center gap-2">
          <div className="text-xs uppercase tracking-wider text-white/60">
            Testcases (CSV)
          </div>
          <Input
            placeholder="Search…"
            value={csvSearch}
            onChange={(e) => {
              setCsvSearch(e.target.value);
              setCsvPage(1);
            }}
            className="ml-3 w-64"
          />
          {!!csvInfo.headers.length && (
            <div className="ml-auto text-xs text-white/60">
              Page {csvPage}/{totalPages} • {filteredRows.length} rows
            </div>
          )}
       
        </div>

        {csvInfo.headers.length ? (
          <div className="mt-2 overflow-auto rounded border border-white/10">
            <table className="min-w-full text-sm">
              <thead className="bg-[#101034] text-white/70">
                <tr>
                  {csvInfo.headers.map((h, i) => (
                    <th
                      key={i}
                      className="px-3 py-2 text-left whitespace-nowrap border-b border-white/10"
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {pageRows.map((row, rIdx) => (
                  <tr key={rIdx} className="odd:bg-white/5">
                    {row.map((cell, cIdx) => (
                      <td
                        key={cIdx}
                        className="px-3 py-2 whitespace-pre-wrap border-b border-white/5"
                      >
                        {cell}
                      </td>
                    ))}
                  </tr>
                ))}
                {!pageRows.length && (
                  <tr>
                    <td
                      className="px-3 py-3 text-white/60"
                      colSpan={csvInfo.headers.length}
                    >
                      No rows on this page.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="text-white/60 text-sm mt-2">—</div>
        )}

        {!!csvInfo.headers.length && (
          <div className="flex items-center gap-2 mt-3">
            <Btn onClick={() => setCsvPage((p) => Math.max(1, p - 1))}>
              Prev
            </Btn>
            <Btn onClick={() => setCsvPage((p) => Math.min(totalPages, p + 1))}>
              Next
            </Btn>
          </div>
        )}
      </div>
    </Card>
  );
}

/* ---------- minor components ---------- */
function StatCard({ label, value, tone = "muted" }) {
  const borders = {
    green: "border-emerald-300/30",
    red: "border-rose-300/30",
    muted: "border-white/15",
  }[tone];
  const color = {
    green: "text-emerald-300",
    red: "text-rose-300",
    muted: "text-white",
  }[tone];
  return (
    <div className={`rounded border ${borders} p-3`}>
      <div className="text-[11px] text-white/60">{label}</div>
      <div className={`text-xl ${color}`}>{value}</div>
    </div>
  );
}

function ArtifactPreview({ artifact, username, projectName }) {
  const { url, mime, name, s3Key } = artifact || {};
  const lower = (mime || "").toLowerCase();
  if (!url) return <div className="p-3 text-white/60 text-sm">No URL.</div>;
  if (lower.startsWith("image/")) {
    return (
      <div className="bg-black/30 p-2 flex items-center justify-center">
        <img
          src={url}
          alt={name || s3Key}
          className="max-h-64 object-contain"
        />
      </div>
    );
  }
  if (lower.includes("json"))
    return (
      <JsonPreview
        artifact={artifact}
        username={username}
        projectName={projectName}
      />
    );
  if (lower.includes("csv") || lower.startsWith("text/"))
    return (
      <TextPreview
        artifact={artifact}
        username={username}
        projectName={projectName}
      />
    );
  return (
    <div className="p-3 text-white/60 text-sm">
      Preview not supported.{" "}
      <a className="underline" href={url} target="_blank" rel="noreferrer">
        Open
      </a>
    </div>
  );
}

function JsonPreview({ artifact, username, projectName }) {
  const [txt, setTxt] = useState("");
  useEffect(() => {
    (async () => {
      try {
        const t = await fetchTextWithFallback(artifact, username, projectName);
        try {
          setTxt(JSON.stringify(JSON.parse(t), null, 2));
        } catch {
          setTxt(t);
        }
      } catch {
        setTxt("Failed to fetch JSON.");
      }
    })();
  }, [artifact, username, projectName]);
  return (
    <pre className="bg-black/40 border-t border-white/10 p-3 text-xs max-h-64 overflow-auto whitespace-pre">
      {txt || "—"}
    </pre>
  );
}

function TextPreview({ artifact, username, projectName }) {
  const [txt, setTxt] = useState("");
  useEffect(() => {
    (async () => {
      try {
        const t = await fetchTextWithFallback(artifact, username, projectName);
        setTxt(t);
      } catch {
        setTxt("Failed to fetch text.");
      }
    })();
  }, [artifact, username, projectName]);
  return (
    <pre className="bg-black/40 border-t border-white/10 p-3 text-xs max-h-64 overflow-auto whitespace-pre-wrap">
      {txt || "—"}
    </pre>
  );
}
