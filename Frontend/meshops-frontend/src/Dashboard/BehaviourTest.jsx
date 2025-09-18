// src/pages/BehaviourTest.jsx
import React, { useEffect, useMemo, useState } from "react";
import Editor from "@monaco-editor/react";
import { ensureProject, startRun, getRunStatus, listArtifacts } from "../api/behaviour";
import { StorageAPI } from "../api/storage";
import {
  PieChart, Pie, Cell, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from "recharts";

/* ---------- endpoints (must match backend) ---------- */
const BASE_RUNS = "http://localhost:8082/api/runs";
const BASE_REFINER = "http://localhost:8082/api/refiner";
const BASE_SCENARIOS = "http://localhost:8082/api/scenarios";
const BASE_PLANS = "http://localhost:8082/api/plans";
const BASE_STORAGE = "http://localhost:8081";
const BUCKET_PREFIX = "s3://my-users-meshops-bucket";

/* ---------- small UI atoms ---------- */
const Btn = ({ className = "", variant = "default", ...p }) => {
  const variants = {
    default: "border-white/15 hover:bg-white/10",
    primary:
      "border-transparent bg-gradient-to-b from-[#D4AF37]/80 to-[#B69121]/80 hover:from-[#D4AF37] hover:to-[#B69121] text-black font-medium shadow-lg shadow-yellow-800/20",
    ghost: "border-transparent hover:bg-white/5",
  };
  return (
    <button
      {...p}
      className={`px-3 py-2 text-xs rounded transition border ${variants[variant] || variants.default} ${className}`}
    />
  );
};
const Card = ({ title, children }) => (
  <div
    className="rounded-2xl border p-4 space-y-3"
    style={{ borderColor: "rgba(255,255,255,0.09)", background: "#0F0F1A" }}
  >
    {title ? <div className="font-semibold text-white/80 mb-2">{title}</div> : null}
    {children}
  </div>
);
const Input = (props) => (
  <input
    {...props}
    className="bg-[#121235] border border-white/10 rounded px-2 py-2 text-sm text-white placeholder-white/40"
  />
);

/* ---------- helpers ---------- */
const TEXT_EXT = new Set(["py", "yaml", "yml", "json", "js", "ts", "txt", "md"]);
const ext = (n = "") => (n.includes(".") ? n.split(".").pop().toLowerCase() : "");

// robust CSV parser
const parseCSV = (text) => {
  if (!text || !text.trim()) return { headers: [], rows: [] };
  const rows = [];
  let cur = [],
    val = "",
    q = false;
  const pushCell = () => {
    cur.push(val);
    val = "";
  };
  const pushRow = () => {
    rows.push(cur);
    cur = [];
  };
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) {
      if (ch === '"' && text[i + 1] === '"') {
        val += '"';
        i++;
      } else if (ch === '"') {
        q = false;
      } else {
        val += ch;
      }
    } else {
      if (ch === '"') q = true;
      else if (ch === ",") pushCell();
      else if (ch === "\n" || ch === "\r") {
        if (ch === "\r" && text[i + 1] === "\n") i++;
        pushCell();
        pushRow();
      } else {
        val += ch;
      }
    }
  }
  pushCell();
  if (cur.length && cur.some((c) => String(c).trim() !== "")) pushRow();
  if (!rows.length) return { headers: [], rows: [] };
  const headers = rows[0].map((h) => String(h || "").trim());
  const body = rows.slice(1).filter((r) => r.some((c) => String(c).trim() !== ""));
  return { headers, rows: body };
};

const s3DownloadUrl = (username, projectName, folder, fileName) =>
  `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(
    projectName
  )}` + `/download/${encodeURIComponent(fileName)}?folder=${encodeURIComponent(folder || "")}`;

/* ---------- API helpers ---------- */
async function approvePlanFull({ username, projectName }) {
  const driverKey = `${username}/${projectName}/pre-processed/driver.py`;
  const testsKey = `${username}/${projectName}/pre-processed/tests.yaml`;
  const s3Prefix = `${BUCKET_PREFIX}/${username}/${projectName}/pre-processed`;
  const url = `${BASE_PLANS}/approve`;
  const payload = { username, projectName, driverKey, testsKey, s3Prefix, approved: true };
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error((await res.text()) || "Approve failed");
  return res.json();
}
async function saveScenarioPrompt(u, p, message, runId) {
  const url = `${BASE_SCENARIOS}/${encodeURIComponent(u)}/${encodeURIComponent(p)}/prompts`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, runId: runId ?? null }),
  });
  if (!res.ok) throw new Error((await res.text()) || "Failed to save prompt");
  return res.json();
}
async function listScenarioPrompts(username, projectName, limit = 12) {
  const url = `${BASE_SCENARIOS}/${encodeURIComponent(username)}/${encodeURIComponent(
    projectName
  )}/prompts?limit=${limit}`;
  try {
    const res = await fetch(url);
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}
async function refineTests(username, projectName, runId, feedback, autoRun) {
  const url = `${BASE_REFINER}/${encodeURIComponent(username)}/${encodeURIComponent(
    projectName
  )}/refine?runId=${encodeURIComponent(runId)}&autoRun=${autoRun ? "true" : "false"}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userFeedback: feedback || "" }),
  });
  if (!res.ok) throw new Error((await res.text()) || "Refine failed");
  return res.json();
}
async function generateDriverAndTests(username, projectName, brief = "", files = []) {
  const url = `${BASE_PLANS}/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/generate`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      brief: brief || "Create a deterministic driver.py and tests.yaml (with scenarios) for behaviour testing.",
      files: files.length ? files : ["train.py", "predict.py", "dataset.csv"],
    }),
  });
  if (!res.ok) throw new Error((await res.text()) || "Generate failed");
  return res.json();
}
async function generateTestsYamlOnly(username, projectName, brief = "") {
  const url = `${BASE_PLANS}/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/tests/new`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      brief:
        brief ||
        "Generate 50–100 realistic behaviour test scenarios (edge/boundary/typical) that the driver can evaluate directly.",
      activate: true,
      files: [],
    }),
  });
  if (!res.ok) throw new Error((await res.text()) || "Test generation failed");
  return res.json();
}

/* ========================================================================== */
export default function BehaviourTest() {
  const username = localStorage.getItem("username") || "pg";

  // state
  const [projects, setProjects] = useState([]);
  const [projectName, setProjectName] = useState(localStorage.getItem("activeProject") || "");
  const [files, setFiles] = useState([]);
  const [activeFile, setActiveFile] = useState(null);
  const [runId, setRunId] = useState(null);
  const [consoleLines, setConsoleLines] = useState([]);
  const [metrics, setMetrics] = useState(null);
  const [confusionURL, setConfusionURL] = useState("");
  const [csvInfo, setCsvInfo] = useState({ headers: [], rows: [] });
  const [runs, setRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [trend, setTrend] = useState([]);
  const [scenarioInput, setScenarioInput] = useState("");
  const [prompts, setPrompts] = useState([]);
  const [autoRunAfterRefine, setAutoRunAfterRefine] = useState(true);
  const [busy, setBusy] = useState(false);
  const [genBusy, setGenBusy] = useState(false);

  /* Project bootstrap */
  useEffect(() => {
    (async () => {
      if (!projectName) return;
      try {
        const s3Prefix = `${BUCKET_PREFIX}/${username}/${projectName}/pre-processed`;
        await ensureProject({ username, projectName, s3Prefix });
      } catch (e) {
        console.warn("ensureProject failed:", e?.message || e);
      }
    })();
  }, [username, projectName]);

  /* load projects */
  useEffect(() => {
    (async () => {
      try {
        const list = await StorageAPI.listProjects(username);
        setProjects(list || []);
        if (!projectName || !list.includes(projectName)) {
          const next = list[0] || "";
          setProjectName(next);
          if (next) localStorage.setItem("activeProject", next);
        }
      } catch {
        setProjects([]);
      }
    })();
  }, [username]);

  /* load prompts */
  useEffect(() => {
    (async () => {
      if (!projectName) return;
      const list = await listScenarioPrompts(username, projectName, 12);
      setPrompts(list || []);
    })();
  }, [username, projectName]);
  const refreshPrompts = async () => setPrompts(await listScenarioPrompts(username, projectName, 12));

  /* load pre-processed files */
  const loadEditorsFromPreProcessed = async () => {
    try {
      const items = await StorageAPI.listFiles(username, projectName, "pre-processed");
      const fileNames = (items || []).filter((n) => n && !n.endsWith("/") && TEXT_EXT.has(ext(n)));
      const loaded = await Promise.all(
        fileNames.map(async (f) => {
          const txt = await StorageAPI.fetchTextFile(username, projectName, f, "pre-processed");
          const e = ext(f);
          const language =
            e === "py" ? "python" : e === "yaml" || e === "yml" ? "yaml" : e === "json" ? "json" : "plaintext";
          return { name: f, content: txt ?? "", language };
        })
      );
      loaded.sort((a, b) => {
        if (a.name === "tests.yaml") return -1;
        if (b.name === "tests.yaml") return 1;
        return a.name.localeCompare(b.name);
      });
      setFiles(loaded);
      setActiveFile(loaded[0]?.name || null);
    } catch {
      setFiles([]);
      setActiveFile(null);
    }
  };
  useEffect(() => {
    if (projectName) loadEditorsFromPreProcessed();
  }, [projectName]);

  /* refresh runs & metrics trend */
  const refreshRuns = async () => {
    if (!projectName) return;
    try {
      const items = await StorageAPI.listFiles(username, projectName, "artifacts-behaviour");
      const parsed = (items || [])
        .map((n) => String(n).trim())
        .map((n) => {
          const m = /^run_(\d+)\/?$/.exec(n);
          return m ? { runId: Number(m[1]) } : null;
        })
        .filter(Boolean)
        .sort((a, b) => b.runId - a.runId);
      setRuns(parsed);
      if (!selectedRunId && parsed.length) setSelectedRunId(parsed[0].runId);

      const take = parsed.slice(0, 12);
      const results = [];
      for (const r of take) {
        try {
          const txt = await StorageAPI.fetchTextFile(
            username,
            projectName,
            "metrics.json",
            `artifacts-behaviour/run_${r.runId}`
          );
          const mj = JSON.parse(txt || "{}");
          const acc =
            typeof mj.accuracy === "number"
              ? mj.accuracy
              : typeof mj.acc === "number"
              ? mj.acc
              : typeof mj["accuracy_score"] === "number"
              ? mj["accuracy_score"]
              : typeof mj["Accuracy"] === "number"
              ? mj["Accuracy"]
              : null;
          results.push({ runId: r.runId, acc });
        } catch {
          results.push({ runId: r.runId, acc: null });
        }
      }
      setTrend(results.reverse());
    } catch {
      setRuns([]);
      setTrend([]);
    }
  };
  useEffect(() => {
    refreshRuns();
  }, [projectName]);

  /* console streaming */
  useEffect(() => {
    if (!runId) return;
    const t = setInterval(async () => {
      try {
        const res = await fetch(`${BASE_RUNS}/${encodeURIComponent(runId)}/console`);
        if (res.ok) setConsoleLines((await res.text()).split("\n"));
      } catch {}
    }, 2000);
    return () => clearInterval(t);
  }, [runId]);

  /* s3 upload helper */
  const uploadS3Text = async (key, content, contentType) => {
    const parts = (key || "").split("/").filter(Boolean);
    const fileName = parts.pop() || "";
    const folder = parts.join("/");
    return await StorageAPI.uploadTextFile(
      username,
      projectName,
      fileName,
      content,
      folder,
      contentType || "text/plain"
    );
  };

  /* GENERATE (single button smart mode in header) */
  const generateTestsSmart = async () => {
    if (!projectName) return;
    setGenBusy(true);
    try {
      const s3Prefix = `${BUCKET_PREFIX}/${username}/${projectName}/pre-processed`;
      await ensureProject({ username, projectName, s3Prefix });
      let items = [];
      try {
        items = await StorageAPI.listFiles(username, projectName, "pre-processed");
      } catch {}
      const hasDriver = (items || []).some((f) => f.toLowerCase().includes("driver.py"));

      if (!hasDriver) {
        await generateDriverAndTests(
          username,
          projectName,
          "Create a deterministic driver.py and tests.yaml (with scenarios).",
          ["train.py", "predict.py", "dataset.csv"]
        );
      } else {
        const brief =
          (scenarioInput || "").trim() ||
          `Generate 50–100 realistic behaviour test scenarios.
          - Each scenario MUST have an "input" block with feature values
          - And an "expected" block with { kind: "classification", label: <0/1> }
            if task is classification, or with numeric value if regression.
          Do not omit expected labels.`;
        await generateTestsYamlOnly(username, projectName, brief);
      }
      await loadEditorsFromPreProcessed();
      setActiveFile("tests.yaml");
    } catch (e) {
      alert("Generate failed: " + (e?.message || e));
    } finally {
      setGenBusy(false);
    }
  };

  /* Save & Approve */
  const saveAndApprove = async () => {
    if (!projectName) return;
    setBusy(true);
    try {
      await Promise.all(files.map((f) => uploadS3Text(`pre-processed/${f.name}`, f.content)));
      await approvePlanFull({ username, projectName });
      setConsoleLines((ls) => [...ls, "Approved current plan."]);
    } catch (e) {
      alert("Save & Approve failed: " + (e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  /* Approve & Run (current plan) */
  const approveAndRun = async () => {
    if (!projectName) return;
    setBusy(true);
    try {
      await Promise.all(files.map((f) => uploadS3Text(`pre-processed/${f.name}`, f.content)));
      await approvePlanFull({ username, projectName });
      const r = await startRun({ username, projectName, task: "classification" });
      const newRunId = r?.data?.runId ?? r?.runId ?? null;
      setRunId(newRunId);
      setConsoleLines(["Starting run…"]);
    } catch (e) {
      alert("Approve & Run failed: " + (e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  /* run completion watcher */
  useEffect(() => {
    if (!runId) return;
    const timer = setInterval(async () => {
      try {
        const res = await getRunStatus(runId);
        const isDone = res?.data?.isDone ?? res?.isDone ?? false;
        if (isDone) {
          clearInterval(timer);
          const artsRes = await listArtifacts(runId);
          const arr = artsRes?.data ?? artsRes ?? [];
          const metricsArt = arr.find((a) => (a.name || "").toLowerCase().includes("metrics"));
          const confArt = arr.find((a) => (a.name || "").toLowerCase().includes("confusion"));
          const csvArt = arr.find((a) => (a.name || "").endsWith(".csv"));

          try {
            if (metricsArt?.url) {
              const t = await fetch(metricsArt.url).then((r) => (r.ok ? r.text() : ""));
              setMetrics(t ? JSON.parse(t) : null);
            } else setMetrics(null);
          } catch {
            setMetrics(null);
          }

          if (confArt?.url) setConfusionURL(confArt.url);
          else setConfusionURL("");

          try {
            if (csvArt?.url) {
              const t = await fetch(csvArt.url).then((r) => (r.ok ? r.text() : ""));
              setCsvInfo(parseCSV(t));
            } else setCsvInfo({ headers: [], rows: [] });
          } catch {
            setCsvInfo({ headers: [], rows: [] });
          }

          setConsoleLines((ls) => [...ls, "Run completed"]);
          setTimeout(refreshRuns, 500);
        }
      } catch {}
    }, 3000);
    return () => clearInterval(timer);
  }, [runId]);

  /* load past run */
  const loadRunFromHistory = async (rid) => {
    setSelectedRunId(rid);
    try {
      try {
        const txt = await StorageAPI.fetchTextFile(
          username,
          projectName,
          "metrics.json",
          `artifacts-behaviour/run_${rid}`
        );
        setMetrics(JSON.parse(txt || "{}"));
      } catch {
        setMetrics(null);
      }
      setConfusionURL(
        s3DownloadUrl(username, projectName, `artifacts-behaviour/run_${rid}`, "confusion_matrix.png")
      );
      try {
        const csvText = await StorageAPI.fetchTextFile(
          username,
          projectName,
          "tests.csv",
          `artifacts-behaviour/run_${rid}`
        );
        setCsvInfo(parseCSV(csvText));
      } catch {
        setCsvInfo({ headers: [], rows: [] });
      }
    } catch {}
  };
  useEffect(() => {
    if (selectedRunId) loadRunFromHistory(selectedRunId);
  }, [selectedRunId]);

  /* feedback actions */
  const onSavePrompt = async () => {
    if (!scenarioInput.trim()) return;
    try {
      await saveScenarioPrompt(username, projectName, scenarioInput.trim(), selectedRunId || runId || null);
      setScenarioInput("");
      refreshPrompts();
      setConsoleLines((ls) => [...ls, "Prompt saved."]);
    } catch (e) {
      alert("Failed to save feedback: " + (e.message || e));
    }
  };

  // NEW: Generate scenarios from the chat box into tests.yaml (activates it)
  const onGenerateScenarios = async () => {
    const brief = (scenarioInput || "").trim();
    if (!brief) {
      alert("Type a prompt first.");
      return;
    }
    setBusy(true);
    try {
      await generateTestsYamlOnly(username, projectName, brief);
      await loadEditorsFromPreProcessed();
      setActiveFile("tests.yaml");
      setConsoleLines((ls) => [...ls, "Scenarios generated into tests.yaml"]);
    } catch (e) {
      alert("Scenario generation failed: " + (e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  // NEW: Approve + run using current driver.py + tests.yaml (so you can run just those 5 scenarios)
  const onRunScenariosOnly = async () => {
    setBusy(true);
    try {
      await Promise.all(files.map((f) => uploadS3Text(`pre-processed/${f.name}`, f.content)));
      await approvePlanFull({ username, projectName });
      const r = await startRun({ username, projectName, task: "classification" });
      const newRunId = r?.data?.runId ?? r?.runId ?? null;
      setRunId(newRunId);
      setConsoleLines(["Running with scenarios only…"]);
    } catch (e) {
      alert("Run failed: " + (e.message || e));
    } finally {
      setBusy(false);
    }
  };

  const onRefine = async () => {
    if (!selectedRunId && !runId) {
      alert("Run once before refining.");
      return;
    }
    setBusy(true);
    try {
      const srcRun = selectedRunId || runId;
      const out = await refineTests(username, projectName, srcRun, scenarioInput.trim(), autoRunAfterRefine);
      refreshPrompts();
      refreshRuns();
      if (out?.newRunId) {
        setRunId(out.newRunId);
        setConsoleLines(["Refine activated; auto-run started…"]);
      } else {
        setConsoleLines([`Refine activated: ${out?.canonicalKey || "tests.yaml updated"}`]);
      }
      await loadEditorsFromPreProcessed();
    } catch (e) {
      alert("Refine failed: " + (e.message || e));
    } finally {
      setBusy(false);
    }
  };

  /* derived scenario table */
  const [csvSearch, setCsvSearch] = useState("");
  const [csvPage, setCsvPage] = useState(1);
  const pageSize = 10;
  const filteredRows = useMemo(() => {
    if (!csvSearch.trim()) return csvInfo.rows;
    const q = csvSearch.toLowerCase();
    return csvInfo.rows.filter((r) => r.some((c) => (String(c || "")).toLowerCase().includes(q)));
  }, [csvInfo, csvSearch]);
  const pageRows = filteredRows.slice((csvPage - 1) * pageSize, csvPage * pageSize);

  // editor gate
  const hasDriver = files.some((f) => f.name === "driver.py");
  const hasTests = files.some((f) => f.name === "tests.yaml");
  const readyForEditor = hasDriver && hasTests;

  /* ========================== UI ========================== */
  return (
    <div className="min-h-screen text-white relative">
      {/* header */}
      <div className="sticky top-0 z-20 bg-[#0B0B1A]/80 backdrop-blur border-b border-white/10 px-5 py-4 flex items-center justify-between">
        <div>
          <div className="text-xs text-white/60">Testing Suite</div>
          <div className="text-xl font-semibold">Behaviour Testing</div>
        </div>
        <div className="flex items-center gap-3">
          <select
            value={projectName}
            onChange={(e) => {
              setProjectName(e.target.value);
              localStorage.setItem("activeProject", e.target.value);
            }}
            className="bg-transparent border px-2 py-1 rounded"
          >
            {projects.map((p) => (
              <option key={p} value={p} className="bg-black">
                {p}
              </option>
            ))}
          </select>
          <Btn onClick={generateTestsSmart} disabled={genBusy || !projectName}>
            {genBusy ? "Generating…" : hasDriver ? "Generate tests.yaml (50–100 scenarios)" : "Generate driver + tests.yaml"}
          </Btn>
          {readyForEditor && (
            <>
              <Btn onClick={saveAndApprove} disabled={busy || !projectName}>
                Save & Approve
              </Btn>
              <Btn variant="primary" onClick={approveAndRun} disabled={busy || !projectName}>
                {busy ? "Starting…" : "Approve & Run"}
              </Btn>
            </>
          )}
        </div>
      </div>

      {/* main split */}
      <div className="max-w-[1400px] mx-auto p-5 grid grid-cols-1 lg:grid-cols-[360px_minmax(0,1fr)] gap-5">
        {/* left */}
        <div className="space-y-5">
          <Card title="Scenarios & Refinement">
            <textarea
              value={scenarioInput}
              onChange={(e) => setScenarioInput(e.target.value)}
              rows={6}
              placeholder='Describe refinements or paste a generation request (e.g., "Generate 5 realistic credit card fraud detection scenarios with boundary values …").'
              className="w-full bg-[#121235] border border-white/10 rounded px-3 py-2 text-sm text-white placeholder-white/40"
            />
            <div className="flex flex-wrap items-center gap-3 mt-2">
              <Btn onClick={onSavePrompt} disabled={busy || !scenarioInput.trim()}>
                Save Prompt
              </Btn>
              <Btn onClick={onGenerateScenarios} disabled={busy || !scenarioInput.trim()}>
                Generate Scenarios
              </Btn>
              <Btn onClick={onRunScenariosOnly} disabled={busy}>
                Run Scenarios Only
              </Btn>
              <Btn variant="primary" onClick={onRefine} disabled={busy || (!selectedRunId && !runId)}>
                {busy ? "Refining…" : "Refine Tests"}
              </Btn>
              <label className="text-xs flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={autoRunAfterRefine}
                  onChange={(e) => setAutoRunAfterRefine(e.target.checked)}
                />
                Auto-run after refine
              </label>
            </div>
          </Card>

          <Card title="Recent Feedback">
            <div className="space-y-2 max-h-[280px] overflow-auto border border-white/10 rounded p-2">
              {prompts?.length ? (
                prompts.map((p) => (
                  <div key={p.id || p.createdAt || p.message} className="text-sm bg-white/5 rounded p-2">
                    <div className="text-white/80">{p.message}</div>
                    <div className="text-[11px] text-white/50 mt-1">
                      {p.createdAt ? new Date(p.createdAt).toLocaleString() : ""}
                      {p.runId ? ` • run_${p.runId}` : ""}
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-white/60 text-sm">No prompts yet.</div>
              )}
            </div>
            <div className="mt-2">
              <Btn variant="ghost" onClick={refreshPrompts}>
                Refresh
              </Btn>
            </div>
          </Card>

          <Card title="Console">
            <pre className="bg-black/30 rounded p-3 text-xs max-h-[260px] overflow-auto">
              {runId ? consoleLines.join("\n") || "Streaming…" : "Console appears after run starts"}
            </pre>
          </Card>

          <Card title="Previous Runs">
            <div className="flex items-center justify-between mb-3">
              <div className="text-xs text-white/60">Accuracy trend (recent {trend.length || 0})</div>
              <Btn variant="ghost" onClick={refreshRuns}>
                Refresh
              </Btn>
            </div>
            {trend.length ? (
              <div className="h-24 mb-4">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={trend.map((d, i) => ({ i, acc: d.acc }))}>
                    <XAxis dataKey="i" hide />
                    <YAxis domain={[0, 1]} hide />
                    <Tooltip
                      formatter={(v) => (v == null ? "—" : Number(v).toFixed(4))}
                      labelFormatter={(i) => `Run ${trend[i]?.runId ?? ""}`}
                    />
                    <Line type="monotone" dataKey="acc" stroke="#FFD700" dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <div className="text-white/50 text-sm mb-4">No trend yet.</div>
            )}
            {runs.length ? (
              <div className="flex flex-wrap gap-2">
                {runs.map((r) => (
                  <Btn
                    key={r.runId}
                    className={
                      (selectedRunId === r.runId ? "border-yellow-400/60 text-yellow-300 " : "") + "px-3 py-1.5"
                    }
                    onClick={() => setSelectedRunId(r.runId)}
                  >
                    run_{r.runId}
                  </Btn>
                ))}
              </div>
            ) : (
              <div className="text-white/60 text-sm">No previous runs yet.</div>
            )}
          </Card>
        </div>

        {/* right */}
        <div className="space-y-5">
          <Card title="Project Files (pre-processed)">
            <div className="flex gap-2 mb-3 border-b border-white/10 overflow-x-auto">
              {readyForEditor &&
                files.map((f) => (
                  <button
                    key={f.name}
                    onClick={() => setActiveFile(f.name)}
                    className={`px-3 py-1 text-sm ${
                      activeFile === f.name ? "border-b-2 border-yellow-400 text-yellow-300" : "text-white/60"
                    }`}
                  >
                    {f.name}
                  </button>
                ))}
              {!readyForEditor && (
                <div className="text-xs text-white/50 px-2 py-1">
                  driver.py & tests.yaml not ready. Click <b>Generate driver + tests.yaml</b>.
                </div>
              )}
            </div>

            {readyForEditor && activeFile && (
              <Editor
                height="420px"
                language={files.find((f) => f.name === activeFile)?.language || "plaintext"}
                theme="vs-dark"
                value={files.find((f) => f.name === activeFile)?.content || ""}
                onChange={(v) =>
                  setFiles((prev) => prev.map((f) => (f.name === activeFile ? { ...f, content: v || "" } : f)))
                }
                options={{ minimap: { enabled: false }, fontSize: 14 }}
              />
            )}

            {readyForEditor && (
              <div className="flex gap-2 mt-3">
                <Btn onClick={saveAndApprove} disabled={busy || !projectName}>
                  Save & Approve
                </Btn>
                <Btn variant="primary" onClick={approveAndRun} disabled={busy || !projectName}>
                  {busy ? "Starting…" : "Approve & Run"}
                </Btn>
                <Btn variant="ghost" onClick={loadEditorsFromPreProcessed} disabled={busy || !projectName}>
                  Reload from S3
                </Btn>
              </div>
            )}
          </Card>

          <Card title="Results">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              {/* metrics */}
              <div>
                <div className="text-sm mb-2">Metrics</div>
                {metrics ? (
                  <ResponsiveContainer width="100%" height={200}>
                    <LineChart
                      data={Object.entries(metrics).map(([k, v]) => ({ name: k, value: typeof v === "number" ? v : null }))}
                    >
                      <CartesianGrid strokeDasharray="3 3" stroke="#555" />
                      <XAxis dataKey="name" stroke="#aaa" />
                      <YAxis stroke="#aaa" />
                      <Tooltip formatter={(v) => (v == null ? "—" : Number(v).toFixed(4))} />
                      <Line type="monotone" dataKey="value" stroke="#FFD700" />
                    </LineChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="text-white/60 text-sm">No metrics yet.</div>
                )}
              </div>

              {/* confusion matrix */}
              <div>
                <div className="text-sm mb-2">Confusion Matrix</div>
                {confusionURL ? (
                  <img src={confusionURL} alt="Confusion" className="max-h-60" />
                ) : (
                  <div className="text-white/60 text-sm">No confusion matrix.</div>
                )}
              </div>

              {/* pass/fail summary */}
              <div>
                <div className="text-sm mb-2">Pass/Fail</div>
                {(() => {
                  const statusIdx = csvInfo.headers.findIndex((h) => {
                    const hl = (h || "").toLowerCase();
                    return hl === "status" || hl === "result";
                  });
                  if (statusIdx === -1) return <div className="text-white/60 text-sm">No summary.</div>;
                  let pass = 0,
                    fail = 0;
                  csvInfo.rows.forEach((r) => {
                    const v = (r[statusIdx] || "").toLowerCase();
                    if (v.includes("pass")) pass++;
                    else if (v.includes("fail")) fail++;
                  });
                  return (
                    <ResponsiveContainer width="100%" height={200}>
                      <PieChart>
                        <Pie
                          data={[
                            { name: "Pass", value: pass },
                            { name: "Fail", value: fail },
                          ]}
                          dataKey="value"
                          outerRadius={80}
                          label
                        >
                          <Cell fill="#22c55e" />
                          <Cell fill="#ef4444" />
                        </Pie>
                      </PieChart>
                    </ResponsiveContainer>
                  );
                })()}
              </div>
            </div>

            {/* CSV table */}
            <div className="mt-4">
              <div className="flex items-center gap-2 mb-2">
                <Input
                  placeholder="Search scenarios…"
                  value={csvSearch}
                  onChange={(e) => {
                    setCsvSearch(e.target.value);
                    setCsvPage(1);
                  }}
                  className="w-64"
                />
                <div className="ml-auto text-xs text-white/60">
                  Page {csvPage} / {Math.max(1, Math.ceil(filteredRows.length / pageSize))}
                </div>
              </div>
              <div className="overflow-auto border border-white/10 rounded">
                <table className="min-w-full text-sm">
                  <thead className="bg[#13131F] text-white/70">
                    <tr>
                      {csvInfo.headers.map((h, i) => (
                        <th key={i} className="px-3 py-2 text-left border-b border-white/10">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {pageRows.map((row, rIdx) => {
                      const resultIdx = csvInfo.headers.findIndex((h) => (h || "").toLowerCase() === "result");
                      return (
                        <tr key={rIdx} className="odd:bg-white/5">
                          {row.map((cell, cIdx) => {
                            const base = "px-3 py-2 border-b border-white/5";
                            if (cIdx === resultIdx) {
                              const v = String(cell || "").toLowerCase();
                              const cls = v.includes("pass")
                                ? "text-emerald-400 font-medium"
                                : v.includes("fail")
                                ? "text-rose-400 font-medium"
                                : "text-white/80";
                              return (
                                <td key={cIdx} className={`${base} ${cls}`}>
                                  {cell}
                                </td>
                              );
                            }
                            return (
                              <td key={cIdx} className={`${base} text-white/80`}>
                                {cell}
                              </td>
                            );
                          })}
                        </tr>
                      );
                    })}
                    {!pageRows.length && (
                      <tr>
                        <td colSpan={csvInfo.headers.length || 1} className="px-3 py-2 text-white/60">
                          No rows
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
              <div className="flex gap-2 mt-2">
                <Btn onClick={() => setCsvPage((p) => Math.max(1, p - 1))} disabled={csvPage <= 1}>
                  Prev
                </Btn>
                <Btn
                  onClick={() =>
                    setCsvPage((p) => Math.min(Math.ceil(filteredRows.length / pageSize), p + 1))
                  }
                  disabled={csvPage >= Math.ceil(filteredRows.length / pageSize)}
                >
                  Next
                </Btn>
              </div>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
