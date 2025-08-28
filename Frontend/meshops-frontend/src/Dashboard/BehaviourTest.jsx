// src/pages/BehaviourTest.jsx
import React, { useEffect, useMemo, useState } from "react";
import Editor from "@monaco-editor/react";
import {
  ensureProject,
  generatePlan,
  approvePlan,
  startRun,
  getRunStatus,
  listArtifacts,
} from "../api/behaviour";
import { StorageAPI } from "../api/storage";
import {
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";

/* ---------- atoms (lean) ---------- */
const Btn = ({ className = "", variant = "default", ...p }) => {
  const variants = {
    default: "border-white/15 hover:bg-white/10",
    primary:
      "border-transparent bg-gradient-to-b from-[#D4AF37]/80 to-[#B69121]/80 hover:from-[#D4AF37] hover:to-[#B69121] text-black font-medium shadow-lg shadow-yellow-800/20",
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
const Card = ({ title, children }) => (
  <div
    className="rounded-2xl border p-4 space-y-3"
    style={{ borderColor: "rgba(255,255,255,0.09)", background: "#0F0F1A" }}
  >
    <div className="font-semibold text-white/80 mb-2">{title}</div>
    {children}
  </div>
);
const Input = (props) => (
  <input
    {...props}
    className={
      (props.className || "") +
      " bg-[#121235] border border-white/10 rounded px-2 py-2 text-sm text-white placeholder-white/40"
    }
  />
);
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
          "w-6 h-6 rounded-full flex items-center justify-center text-[11px] font-bold " +
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
const BASE_RUNS = "http://localhost:8082/api/runs";

function normalizeKey(key) {
  if (!key) return "";
  const m = /^s3:\/\/[^/]+\/(.+)$/.exec(key);
  if (m) return m[1];
  if (key.startsWith("/")) return key.slice(1);
  return key;
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
async function uploadS3Text(username, projectName, key, content, contentType) {
  const { folder, fileName } = splitKey(key);
  return await StorageAPI.uploadTextFile(
    username,
    projectName,
    fileName,
    content,
    folder,
    contentType || "text/plain"
  );
}
function parseCSV(text) {
  const rows = text.split(/\r?\n/).map((r) => r.split(","));
  if (!rows.length) return { headers: [], rows: [] };
  return { headers: rows[0], rows: rows.slice(1).filter((r) => r.join("").trim()) };
}
const s3DownloadUrl = (username, projectName, folder, fileName) =>
  `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(
    username
  )}/projects/${encodeURIComponent(
    projectName
  )}/download/${encodeURIComponent(fileName)}?folder=${encodeURIComponent(folder)}`;

/* ---------- main ---------- */
export default function BehaviourTest() {
  const username = localStorage.getItem("username") || "pg";
  const [projects, setProjects] = useState([]);
  const [projectName, setProjectName] = useState(
    localStorage.getItem("activeProject") || ""
  );

  // states
  const [runId, setRunId] = useState(null);
  const [consoleLines, setConsoleLines] = useState([]);
  const [files, setFiles] = useState([]); // [{name, content, language}]
  const [activeFile, setActiveFile] = useState(null);
  const [metrics, setMetrics] = useState(null);
  const [confusionURL, setConfusionURL] = useState("");
  const [csvInfo, setCsvInfo] = useState({ headers: [], rows: [] });
  const [csvSearch, setCsvSearch] = useState("");
  const [csvPage, setCsvPage] = useState(1);
  const [busy, setBusy] = useState(false);

  // previous runs
  const [runs, setRuns] = useState([]); // [{runId}]
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [trend, setTrend] = useState([]); // [{runId, acc}]

  // step states
  const [stepState, setStepState] = useState({
    ensure: "idle",
    plan: "idle",
    approve: "idle",
    start: "idle",
  });

  /* ---------- load projects ---------- */
  useEffect(() => {
    (async () => {
      try {
        const url = `${BASE_STORAGE}/api/user-storage/${username}/projects`;
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

  /* ---------- refresh previous runs from S3 (+ tiny metrics trend) ---------- */
  const refreshRuns = async () => {
    if (!projectName) return;
    try {
      const items = await StorageAPI.listFiles(username, projectName, "artifacts-behaviour");
      const parsed =
        (items || [])
          .map((n) => String(n).trim())
          .map((n) => {
            const m = /^run_(\d+)\/?$/.exec(n);
            return m ? { runId: Number(m[1]) } : null;
          })
          .filter(Boolean)
          .sort((a, b) => b.runId - a.runId);

      setRuns(parsed);
      if (!selectedRunId && parsed.length) setSelectedRunId(parsed[0].runId);

      // mini trend: try read accuracy from each run's metrics.json (best-effort)
      const take = parsed.slice(0, 12); // recent 12
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
            mj.accuracy ?? mj.acc ?? mj["accuracy_score"] ?? mj["Accuracy"] ?? null;
          results.push({ runId: r.runId, acc: typeof acc === "number" ? acc : null });
        } catch {
          results.push({ runId: r.runId, acc: null });
        }
      }
      setTrend(results.reverse()); // oldest → newest for nicer sparkline
    } catch {}
  };
  useEffect(() => {
    refreshRuns();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectName]);

  /* ---------- realtime console ---------- */
  useEffect(() => {
    if (!runId) return;
    const timer = setInterval(async () => {
      try {
        const res = await fetch(`${BASE_RUNS}/${runId}/console`);
        if (res.ok) {
          const txt = await res.text();
          setConsoleLines(txt.split("\n"));
        }
      } catch {}
    }, 2000);
    return () => clearInterval(timer);
  }, [runId]);

  /* ---------- run behaviour tests ---------- */
const runAll = async () => {
  if (!projectName) return;
  setBusy(true);

  try {
    // Step 1: Ensure project
    setStepState({ ensure: "doing", plan: "idle", approve: "idle", start: "idle" });
    await ensureProject({ username, projectName });
    setStepState((s) => ({ ...s, ensure: "done", plan: "doing" }));

    // Step 2: List files in pre-processed/
    let items = [];
    try {
      items = await StorageAPI.listFiles(username, projectName, "pre-processed");
    } catch (e) {
      console.warn("Could not list pre-processed files", e);
    }

    // Step 3: Detect driver/tests
    const hasDriver = items?.some((f) => f.toLowerCase().includes("driver.py"));
    const hasTests = items?.some((f) => f.toLowerCase().includes("tests.yaml"));

    if (!hasDriver || !hasTests) {
      console.log("Missing driver.py or tests.yaml → calling generatePlan()");
      await generatePlan(username, projectName, {
        brief: "Create behaviour tests for my model",
        files: ["train.py", "predict.py", "data/dataset.csv"], // fallback defaults
      });
    }

    setStepState((s) => ({ ...s, plan: "done", approve: "doing" }));

    // Step 4: Load actual files into editor
    const loaded = await Promise.all(
      (items || []).map(async (f) => {
        const txt = await StorageAPI.fetchTextFile(username, projectName, f, "pre-processed");
        let language = f.endsWith(".py")
          ? "python"
          : f.endsWith(".yaml") || f.endsWith(".yml")
          ? "yaml"
          : f.endsWith(".json")
          ? "json"
          : f.endsWith(".js")
          ? "javascript"
          : f.endsWith(".ts")
          ? "typescript"
          : "plaintext";
        return { name: f, content: txt, language };
      })
    );

    setFiles(loaded);
    if (loaded.length) setActiveFile(loaded[0].name);

  } catch (e) {
    console.error("Error during runAll:", e);
    setStepState((s) => ({ ...s, plan: "fail" }));
  } finally {
    setBusy(false);
  }
};


// inside approveAndRun
const approveAndRun = async () => {
  if (!projectName || !projectName.trim()) {
    alert("Please select a valid project before approving plan.");
    return;
  }
  console.log("➡️ Approving & starting run with", { username, projectName });
  setBusy(true);
  try {
    await Promise.all(
      files.map((f) =>
        uploadS3Text(username, projectName, `pre-processed/${f.name}`, f.content, "text/plain")
      )
    );
    await approvePlan({ username, projectName, approved: true });
    const r = await startRun({ username, projectName, task: "classification" });
    console.log("✅ startRun response:", r?.data);
    setRunId(r?.data?.runId);
    setStepState((s) => ({ ...s, approve: "done", start: "doing" }));
  } catch (e) {
    console.error("❌ approveAndRun failed:", e);
    alert("Error during approveAndRun: " + (e.response?.data?.message || e.message));
    setStepState((s) => ({ ...s, start: "fail" }));
  } finally {
    setBusy(false);
  }
};


  /* ---------- watch artifacts (end of run) ---------- */
  useEffect(() => {
    if (!runId) return;
    const timer = setInterval(async () => {
      try {
        const res = await getRunStatus(runId);
        if (res.data?.isDone) {
          clearInterval(timer);
          setStepState((s) => ({ ...s, start: "done" }));
          const arts = await listArtifacts(runId);
          const arr = arts.data || [];
          const metricsArt = arr.find((a) => (a.name || "").includes("metrics"));
          const confArt = arr.find((a) => (a.name || "").includes("confusion"));
          const csvArt = arr.find((a) => (a.name || "").endsWith(".csv"));
          if (metricsArt) {
            const t = await fetch(metricsArt.url).then((r) => r.text());
            try {
              setMetrics(JSON.parse(t));
            } catch {}
          }
          if (confArt) setConfusionURL(confArt.url);
          if (csvArt) {
            const t = await fetch(csvArt.url).then((r) => r.text());
            setCsvInfo(parseCSV(t));
          }
          // refresh history a bit later so run_NN appears
          setTimeout(refreshRuns, 800);
        }
      } catch {}
    }, 3000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);

  /* ---------- select previous run from S3 → load artifacts ---------- */
  const loadRunFromHistory = async (rid) => {
    setSelectedRunId(rid);

    try {
      // metrics
      try {
        const txt = await StorageAPI.fetchTextFile(
          username,
          projectName,
          "metrics.json",
          `artifacts-behaviour/run_${rid}`
        );
        setMetrics(JSON.parse(txt));
      } catch (e) {
        console.warn(`metrics.json not found for run_${rid}`, e);
        setMetrics(null);
      }

      // confusion
      setConfusionURL(
        s3DownloadUrl(username, projectName, `artifacts-behaviour/run_${rid}`, "confusion_matrix.png")
      );

      // csv
      try {
        const csvText = await StorageAPI.fetchTextFile(
          username,
          projectName,
          "tests.csv",
          `artifacts-behaviour/run_${rid}`
        );
        setCsvInfo(parseCSV(csvText));
        setCsvPage(1);
      } catch {
        setCsvInfo({ headers: [], rows: [] });
      }
    } catch {}
  };

  useEffect(() => {
    if (selectedRunId) loadRunFromHistory(selectedRunId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRunId]);

  /* ---------- derived ---------- */
  const pageSize = 10;
  const filteredRows = useMemo(() => {
    if (!csvSearch.trim()) return csvInfo.rows;
    const q = csvSearch.toLowerCase();
    return csvInfo.rows.filter((r) => r.some((c) => (c || "").toLowerCase().includes(q)));
  }, [csvInfo, csvSearch]);
  const pageRows = filteredRows.slice((csvPage - 1) * pageSize, csvPage * pageSize);
  const statusIdx = csvInfo.headers.findIndex((h) => h.toLowerCase() === "status");
  const passFail = useMemo(() => {
    if (statusIdx === -1) return null;
    let pass = 0,
      fail = 0;
    csvInfo.rows.forEach((r) => {
      const v = (r[statusIdx] || "").toLowerCase();
      if (v.includes("pass")) pass++;
      else if (v.includes("fail")) fail++;
    });
    return { pass, fail, total: csvInfo.rows.length };
  }, [csvInfo, statusIdx]);

  /* ---------- UI ---------- */
  return (
    <div className="min-h-screen text-white relative">
      {/* background glow */}
      <div
        className="pointer-events-none fixed inset-0 blur-3xl opacity-40"
        style={{
          background:
            "radial-gradient(640px 320px at 85% 8%, rgba(212,175,55,0.25), transparent), radial-gradient(420px 240px at 10% 40%, rgba(80,80,120,0.3), transparent)",
        }}
      />

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
          <Btn variant="primary" onClick={runAll} disabled={busy}>
            {busy ? "Running…" : "Run Behaviour Tests"}
          </Btn>
        </div>
      </div>

      <div className="max-w-7xl mx-auto p-5 space-y-6">
        {/* stages + console (always present) */}
        <Card title="Run Overview">
          <div className="grid gap-3 md:grid-cols-2">
            <div className="space-y-3">
              <Step idx={1} label="Ensure project" state={stepState.ensure} />
              <Step idx={2} label="Generate plan (LLM)" state={stepState.plan} />
              <Step idx={3} label="Approve plan" state={stepState.approve} />
              <Step idx={4} label="Start run" state={stepState.start} />
            </div>
            <div>
              <pre className="bg-black/30 rounded p-3 text-xs max-h-60 overflow-auto">
                {runId
                  ? consoleLines.join("\n") || "Streaming…"
                  : "Console will appear after you start a run"}
              </pre>
            </div>
          </div>
        </Card>

        {/* previous runs + tiny trend */}
        <Card title="Previous Runs">
          <div className="flex items-center justify-between mb-3">
            <div className="text-xs text-white/60">
              Accuracy trend (recent {trend.length || 0})
            </div>
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
                    (selectedRunId === r.runId
                      ? "border-yellow-400/60 text-yellow-300 "
                      : " ") + "px-3 py-1.5"
                  }
                  onClick={() => setSelectedRunId(r.runId)}
                >
                  run_{r.runId}
                </Btn>
              ))}
            </div>
          ) : (
            <div className="text-white/60 text-sm">
              No previous runs yet. They’ll appear in <code>artifacts-behaviour/run_*/</code>.
            </div>
          )}
        </Card>


        {/* editors only at stage 3 */}
        {stepState.approve === "doing" && (
          <Card title="Review & Edit Files">
            <div className="flex gap-2 mb-3 border-b border-white/10 overflow-x-auto">
              {files.map((f) => (
                <button
                  key={f.name}
                  onClick={() => setActiveFile(f.name)}
                  className={`px-3 py-1 text-sm ${
                    activeFile === f.name
                      ? "border-b-2 border-yellow-400 text-yellow-300"
                      : "text-white/60"
                  }`}
                >
                  {f.name}
                </button>
              ))}
            </div>
            {activeFile && (
              <Editor
                height="420px"
                language={files.find((f) => f.name === activeFile)?.language || "plaintext"}
                theme="vs-dark"
                value={files.find((f) => f.name === activeFile)?.content || ""}
                onChange={(v) =>
                  setFiles((prev) =>
                    prev.map((f) => (f.name === activeFile ? { ...f, content: v || "" } : f))
                  )
                }
                options={{ minimap: { enabled: false }, fontSize: 14 }}
              />
            )}
            <div className="flex gap-2 mt-3">
              <Btn variant="primary" onClick={approveAndRun} disabled={busy}>
                Approve & Run
              </Btn>
            </div>
          </Card>
        )}

        {/* results */}
        <Card title="Test Results">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <div className="text-sm mb-2">Metrics</div>
              {metrics ? (
                <ResponsiveContainer width="100%" height={200}>
                  <LineChart
                    data={Object.entries(metrics).map(([k, v]) => ({
                      name: k,
                      value: typeof v === "number" ? v : null,
                    }))}
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
            <div>
              <div className="text-sm mb-2">Confusion Matrix</div>
              {confusionURL ? (
                <img src={confusionURL} alt="Confusion" className="max-h-60" />
              ) : (
                <div className="text-white/60 text-sm">No confusion matrix.</div>
              )}
            </div>
            <div>
              <div className="text-sm mb-2">Pass/Fail</div>
              {passFail ? (
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie
                      data={[
                        { name: "Pass", value: passFail.pass },
                        { name: "Fail", value: passFail.fail },
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
              ) : (
                <div className="text-white/60 text-sm">No summary.</div>
              )}
            </div>
          </div>

          {/* CSV table */}
          <div className="mt-4">
            <div className="flex items-center gap-2 mb-2">
              <Input
                placeholder="Search…"
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
                <thead className="bg-[#13131F] text-white/70">
                  <tr>
                    {csvInfo.headers.map((h, i) => (
                      <th key={i} className="px-3 py-2 text-left border-b border-white/10">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((row, rIdx) => (
                    <tr key={rIdx} className="odd:bg-white/5">
                      {row.map((cell, cIdx) => (
                        <td key={cIdx} className="px-3 py-2 border-b border-white/5">
                          {cell}
                        </td>
                      ))}
                    </tr>
                  ))}
                  {!pageRows.length && (
                    <tr>
                      <td colSpan={csvInfo.headers.length} className="px-3 py-2 text-white/60">
                        No rows
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className="flex gap-2 mt-2">
              <Btn onClick={() => setCsvPage((p) => Math.max(1, p - 1))}>Prev</Btn>
              <Btn
                onClick={() =>
                  setCsvPage((p) => Math.min(Math.ceil(filteredRows.length / pageSize), p + 1))
                }
              >
                Next
              </Btn>
            </div>
          </div>
        </Card>
      </div>
    </div>
  );
}
