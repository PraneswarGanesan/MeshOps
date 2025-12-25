// src/pages/BehaviourTest.jsx
import React, { useEffect, useMemo, useState } from "react";
import Editor from "@monaco-editor/react";
import { ensureProject, startRun, getRunStatus, listArtifacts, approvePlan, generateDriverAndTests as genDriverTests, refineByPromptId, generateCatDogClassifier } from "../api/behaviour";
import { StorageAPI } from "../api/storage";
import {
  PieChart, Pie, Cell, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from "recharts";

/* ---------- endpoints (must match backend) ---------- */
 const BASE_RUNS = "http://localhost:8082/api/runs";
 const BASE_SCENARIOS = "http://localhost:8082/api/unit-scenarios";
 const BASE_TESTS = "http://localhost:8082/api/unit-tests";
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
async function approvePlanFull({ username, projectName, versionLabel }) {
  // Approve using artifacts of the selected version
  // Send RELATIVE keys; backend will resolve with project prefix
  const driverKey = `artifacts/versions/${versionLabel}/driver.py`;
  const testsKey = `artifacts/versions/${versionLabel}/tests.yaml`;
  console.log("[approvePlanFull]", { username, projectName, versionLabel, driverKey, testsKey });
  return await approvePlan({ username, projectName, versionLabel, driverKey, testsKey, approved: true });
}
async function saveScenarioPrompt(u, p, versionLabel = "v1", message, runId) {
  const url = `${BASE_SCENARIOS}/${encodeURIComponent(u)}/${encodeURIComponent(p)}/${encodeURIComponent(versionLabel)}/prompts`;
  const res = await fetch(url, {
    method: "POST",
    headers: { 
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*"
    },
    body: JSON.stringify({ message, runId: runId ?? null }),
    credentials: 'include',
    mode: 'cors'
  });
  if (!res.ok) throw new Error((await res.text()) || "Failed to save prompt");
  return res.json();
}
async function listScenarioPrompts(username, projectName, versionLabel = "v1", limit = 12) {
  const url = `${BASE_SCENARIOS}/${encodeURIComponent(username)}/${encodeURIComponent(
    projectName
  )}/${encodeURIComponent(versionLabel)}/prompts?limit=${limit}`;
  try {
    const res = await fetch(url, {
      headers: { 
        "Access-Control-Allow-Origin": "*"
      },
      credentials: 'include',
      mode: 'cors'
    });
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}
async function refineTests(username, projectName, versionLabel, runId, autoRun = false) {
  const url = `${BASE_TESTS}/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/${encodeURIComponent(versionLabel)}/refine?runId=${encodeURIComponent(runId)}&autoRun=${autoRun}`;
  console.log("[refineUnitTests] POST", url);
  const res = await fetch(url, { method: "POST" });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}


async function generateDriverAndTests(username, projectName, versionLabel, brief = "") {
  return await genDriverTests(username, projectName, versionLabel, brief || "Generate driver and tests for this version.");
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
export default function UnitTest() {
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
  const [versions, setVersions] = useState([]);
  const [selectedVersion, setSelectedVersion] = useState("v1");
  const [artifactFiles, setArtifactFiles] = useState([]);
  const [artifactPath, setArtifactPath] = useState(""); // path within artifacts/versions/{selectedVersion}
  const [artifactList, setArtifactList] = useState([]);   // [{name,isDir}]
  const [viewingArtifact, setViewingArtifact] = useState(null);
  const [artifactContent, setArtifactContent] = useState("");
  const [briefInput, setBriefInput] = useState("");
  const [generatingFiles, setGeneratingFiles] = useState(false);

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
      const list = await listScenarioPrompts(username, projectName, selectedVersion, 12);
      setPrompts(list || []);
    })();
  }, [username, projectName, selectedVersion]);
  const refreshPrompts = async () => setPrompts(await listScenarioPrompts(username, projectName, selectedVersion, 12));

  /* load versions */
  const loadVersions = async () => {
    if (!projectName) return;
    console.log('Loading versions for project:', projectName);
    try {
      const items = await StorageAPI.listFiles(username, projectName, "artifacts/versions");
      console.log('Artifacts folder contents:', items);
      const versionDirs = (items || [])
        .map(n => String(n || ''))
        .filter(n => /^v\d+\/?$/.test(n))
        .map(n => n.replace(/\/$/, ''))
        .sort((a, b) => {
          const aNum = parseInt(a.substring(1)) || 0;
          const bNum = parseInt(b.substring(1)) || 0;
          return bNum - aNum; // newest first
        });
      console.log('Found version directories:', versionDirs);
      setVersions(versionDirs.length ? versionDirs : ["v1"]);
      if (!selectedVersion || !versionDirs.includes(selectedVersion)) {
        setSelectedVersion(versionDirs[0] || "v1");
      }
    } catch (error) {
      console.error('Error loading versions:', error);
      if (!versions.length) {
        setVersions(["v1"]);
        setSelectedVersion("v1");
      }
    }
  };

  /* load artifacts from selected version and current path */
  const loadArtifactFiles = async () => {
    if (!projectName || !selectedVersion) return;
    console.log('Loading artifact files for version:', selectedVersion, 'path:', artifactPath || '/');
    try {
      const folder = artifactPath
        ? `artifacts/versions/${selectedVersion}/${artifactPath}`
        : `artifacts/versions/${selectedVersion}`;
      const items = await StorageAPI.listFiles(username, projectName, folder);
      console.log('Artifact items in folder:', folder, items);
      const list = (items || []).map((n) => ({ name: n, isDir: String(n).endsWith('/') }));
      // Sort: folders first, then files; both alphabetically
      list.sort((a,b)=> (a.isDir===b.isDir) ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1);
      setArtifactList(list);
      // keep a convenience list of key text files in this folder
      const files = (items || [])
        .filter(n => n && !n.endsWith('/') && TEXT_EXT.has(ext(n)))
        .sort((a, b) => {
          if (a === 'driver.py') return -1;
          if (b === 'driver.py') return 1;
          if (a === 'tests.yaml') return -1;
          if (b === 'tests.yaml') return 1;
          return a.localeCompare(b);
        });
      setArtifactFiles(files);
    } catch (error) {
      console.error('Error loading artifact files:', error);
      setArtifactList([]);
      setArtifactFiles([]);
    }
  };

  const enterArtifactDir = (dirName) => {
    const clean = String(dirName).replace(/\/$/, '');
    setArtifactPath((p) => (p ? `${p}/${clean}` : clean));
  };
  const upArtifact = () => {
    setArtifactPath((p) => {
      const parts = (p || '').split('/').filter(Boolean);
      parts.pop();
      return parts.join('/');
    });
  };
  
  // Function to handle generating driver.py and tests.yaml
  const handleGenerateDriverAndTests = async () => {
    if (!briefInput.trim()) {
      alert("Please enter a brief description for generation");
      return;
    }
    
    setGeneratingFiles(true);
    try {
      // Use the direct endpoint for cat_dog_im v2
      const result = await generateCatDogClassifier(briefInput);
      console.log("Generation result:", result);
      
      // Refresh artifact files to show newly generated files
      await loadArtifactFiles();
      
      // Clear the brief input
      setBriefInput("");
      alert("Driver and tests generated successfully!");
    } catch (error) {
      console.error("Error generating driver and tests:", error);
      alert("Failed to generate driver and tests: " + (error.message || "Unknown error"));
    } finally {
      setGeneratingFiles(false);
    }
  };

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
    if (projectName) {
      loadEditorsFromPreProcessed();
      loadVersions();
    }
  }, [projectName]);

  useEffect(() => {
    if (projectName && selectedVersion) {
      loadArtifactFiles();
    }
  }, [projectName, selectedVersion]);

  // When artifactPath changes, reload current folder
  useEffect(() => {
    if (projectName && selectedVersion != null) {
      loadArtifactFiles();
    }
  }, [artifactPath]);

  // Reset inner path when switching versions
  useEffect(() => {
    setArtifactPath("");
  }, [selectedVersion]);

  // Also reset when switching projects
  useEffect(() => {
    setArtifactPath("");
  }, [projectName]);

  /* refresh runs & metrics trend */
  const refreshRuns = async () => {
    if (!projectName || !selectedVersion) return;
    try {
      const items = await StorageAPI.listFiles(username, projectName, `artifacts/versions/${selectedVersion}/unit`);
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
            `artifacts/versions/${selectedVersion}/unit/run_${r.runId}`
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
  }, [projectName, selectedVersion]);

  /* console streaming */
  useEffect(() => {
    if (!runId) return;
    const url = `${BASE_RUNS}/${encodeURIComponent(runId)}/console`;
    console.log("[console] start polling", url);
    const t = setInterval(async () => {
      try {
        console.log("[console] GET", url);
        const res = await fetch(url);
        if (res.ok) setConsoleLines((await res.text()).split("\n"));
      } catch (err) {
        console.warn("[console] error", err);
      }
    }, 3000);
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
      // Assuming artifacts already placed per version; approve that version
      await approvePlanFull({ username, projectName, versionLabel: selectedVersion });
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
      console.log("[approveAndRun] selectedVersion=", selectedVersion);
      await approvePlanFull({ username, projectName, versionLabel: selectedVersion });
      const runPayload = { username, projectName, versionName: selectedVersion, task: "classification" };
      console.log("[approveAndRun] startRun payload", runPayload);
      const r = await startRun(runPayload);
      const newRunId = r?.data?.runId ?? r?.runId ?? null;
      setRunId(newRunId);
      setConsoleLines(["Starting run…"]);
      console.log("[approveAndRun] runId=", newRunId);
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
        const status = res?.data?.status ?? res?.status ?? "unknown";
        
        if (isDone) {
          clearInterval(timer);
          const artsRes = await listArtifacts(runId);
          const arr = artsRes?.data ?? artsRes ?? [];
          const metricsArt = arr.find((a) => (a.name || "").toLowerCase().includes("metrics"));
          const confArt = arr.find((a) => (a.name || "").toLowerCase().includes("confusion"));
          const csvArt = arr.find((a) => (a.name || "").endsWith(".csv"));
          const logsArt = arr.find((a) => (a.name || "").toLowerCase().includes("logs"));

          // Check if run failed
          if (status === "Failed" || status === "failed") {
            setConsoleLines((ls) => [...ls, "❌ Run failed - check logs for details"]);
            
            // Try to fetch logs to show error details
            if (logsArt?.url) {
              try {
                const logsText = await fetch(logsArt.url).then((r) => (r.ok ? r.text() : ""));
                if (logsText.includes("corrupted") || logsText.includes("truncated") || logsText.includes("UnpicklingError")) {
                  setConsoleLines((ls) => [...ls, "⚠️ Corrupted model detected - training new model..."]);
                }
              } catch {}
            }
          } else {
            setConsoleLines((ls) => [...ls, "✅ Run completed successfully"]);
          }

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
          `artifacts/versions/${selectedVersion}/unit/run_${rid}`
        );
        setMetrics(JSON.parse(txt || "{}"));
      } catch {
        setMetrics(null);
      }
      setConfusionURL(
        s3DownloadUrl(username, projectName, `artifacts/versions/${selectedVersion}/unit/run_${rid}`, "confusion_matrix.png")
      );
      try {
        const csvText = await StorageAPI.fetchTextFile(
          username,
          projectName,
          "tests.csv",
          `artifacts/versions/${selectedVersion}/unit/run_${rid}`
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
      await saveScenarioPrompt(username, projectName, selectedVersion, scenarioInput.trim(), selectedRunId || runId || null);
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
  setBusy(true);
  try {
    const runRef = selectedRunId || runId;
    if (!runRef) {
      alert("No run selected for refinement.");
      setBusy(false);
      return;
    }
    const out = await refineTests(username, projectName, selectedVersion, runRef, autoRunAfterRefine);
    refreshPrompts();
    refreshRuns();
    setConsoleLines([
      autoRunAfterRefine ? "Refine + auto-run triggered." : "Refinement triggered only."
    ]);
    await loadEditorsFromPreProcessed();
  } catch (e) {
    alert("Refine failed: " + (e.message || e));
  } finally {
    setBusy(false);
  }
};



  /* view artifact file */
  const viewArtifactFile = async (fileName) => {
    if (!projectName || !selectedVersion) return;
    try {
      const folder = artifactPath
        ? `artifacts/versions/${selectedVersion}/${artifactPath}`
        : `artifacts/versions/${selectedVersion}`;
      const content = await StorageAPI.fetchTextFile(
        username,
        projectName,
        fileName,
        folder
      );
      setArtifactContent(content || "");
      setViewingArtifact(fileName);
    } catch (e) {
      // If backend treats this as a folder (like StorageView's fallback), enter it
      const body = (e && (e._body || e.body)) ? (e._body || e.body) : ((e && e.message) || "");
      const status = e && (e._status || e.status);
      const looksLikeFolder =
        (typeof body === 'string' && (body.includes('NoSuchKey') || body.includes('does not exist'))) ||
        [404, 400, 500].includes(status);
      if (looksLikeFolder) {
        // Attempt to navigate into it as a folder
        enterArtifactDir(fileName);
      } else {
        alert(`Failed to load ${fileName}: ${e.message}`);
      }
    }
  };

  const onArtifactItemClick = async (it) => {
    if (it.isDir) {
      enterArtifactDir(it.name);
      return;
    }
    // Try to view; if server says folder, navigate into it
    try {
      await viewArtifactFile(it.name);
    } catch {}
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

  // editor gate - check both pre-processed files AND artifact files
  const hasDriverPreprocessed = files.some((f) => f.name === "driver.py");
  const hasTestsPreprocessed = files.some((f) => f.name === "tests.yaml");
  const hasDriverArtifact = artifactFiles.includes("driver.py");
  const hasTestsArtifact = artifactFiles.includes("tests.yaml");
  const hasDriver = hasDriverPreprocessed || hasDriverArtifact;
  const hasTests = hasTestsPreprocessed || hasTestsArtifact;
  const readyForEditor = hasDriverPreprocessed && hasTestsPreprocessed;
  const readyForRun = hasDriver && hasTests; // Can run if files exist in either location

  /* ========================== UI ========================== */
  return (
    <div className="min-h-screen text-white relative">
      {/* Premium Unit Testing Header */}
      <div className="sticky top-0 z-20 backdrop-blur-xl border-b"
          style={{ 
            background: 'rgba(10, 10, 15, 0.85)',
            borderColor: 'rgba(217, 158, 40, 0.15)',
            boxShadow: '0 4px 24px rgba(0, 0, 0, 0.4)'
          }}>
        <div className="px-6 py-5 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-amber-500 via-amber-600 to-blue-600 shadow-lg shadow-amber-500/30 flex items-center justify-center">
              <span className="text-white font-bold text-lg">U</span>
            </div>
            <div>
              <div className="text-xs font-medium uppercase tracking-wider" 
                  style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
                Testing Suite
              </div>
              <div className="text-xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 via-amber-200 to-blue-300">
                Unit Testing
              </div>
            </div>
          </div>
          
          <div className="flex items-center gap-3">
            <select
              value={projectName}
              onChange={(e) => {
                setProjectName(e.target.value);
                localStorage.setItem("activeProject", e.target.value);
              }}
              className="bg-transparent border rounded-xl px-4 py-2.5 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)] hover:border-amber-400/30"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.2)',
                background: 'rgba(0, 0, 0, 0.3)',
                color: 'white'
              }}
            >
              {projects.map((p) => (
                <option key={p} value={p} className="bg-black">
                  {p}
                </option>
              ))}
            </select>
            
            <select
              value={selectedVersion}
              onChange={(e) => setSelectedVersion(e.target.value)}
              className="bg-transparent border rounded-xl px-4 py-2.5 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)] hover:border-amber-400/30"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.2)',
                background: 'rgba(0, 0, 0, 0.3)',
                color: 'white'
              }}
            >
              {versions.map((v) => (
                <option key={v} value={v} className="bg-black">
                  {v}
                </option>
              ))}
            </select>
            
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
            {!readyForEditor && readyForRun && (
              <Btn variant="primary" onClick={approveAndRun} disabled={busy || !projectName}>
                {busy ? "Starting…" : "Run Tests"}
              </Btn>
            )}
          </div>
        </div>
      </div>

      {/* main split */}
      <div className="max-w-[1400px] mx-auto p-5 grid grid-cols-1 lg:grid-cols-[360px_minmax(0,1fr)] gap-5">
        {/* left */}
        <div className="space-y-6">
        {/* Scenarios & Refinement Card */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
            style={{ 
              borderColor: 'rgba(217, 158, 40, 0.15)', 
              background: 'rgba(15, 15, 24, 0.6)',
              boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
            }}>
          <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
              style={{
                background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer 3s infinite'
              }} />
          
          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                <span className="text-amber-400 text-sm">✨</span>
              </div>
              <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                Scenarios & Refinement
              </div>
            </div>
            
            <textarea
              value={scenarioInput}
              onChange={(e) => setScenarioInput(e.target.value)}
              rows={6}
              placeholder='Describe refinements or paste a generation request (e.g., "Generate 5 realistic credit card fraud detection scenarios with boundary values…").'
              className="w-full rounded-xl border px-4 py-3 text-sm text-white placeholder-white/40 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)] resize-none"
              style={{ 
                background: 'rgba(0, 0, 0, 0.4)', 
                borderColor: 'rgba(217, 158, 40, 0.2)'
              }}
            />
            
            <div className="flex flex-wrap items-center gap-2 mt-4">
              <Btn onClick={onSavePrompt} disabled={busy || !scenarioInput.trim()}>
                💾 Save Prompt
              </Btn>
              <Btn onClick={onGenerateScenarios} disabled={busy || !scenarioInput.trim()}>
                ⚡ Generate
              </Btn>
              <Btn onClick={onRunScenariosOnly} disabled={busy}>
                ▶️ Run Only
              </Btn>
              <Btn variant="primary" onClick={onRefine} disabled={busy || (!selectedRunId && !runId)}>
                {busy ? "⏳ Refining…" : "✨ Refine Tests"}
              </Btn>
              <label className="text-xs flex items-center gap-2 px-3 py-2 rounded-lg border cursor-pointer transition-all duration-200 hover:bg-amber-500/5"
                    style={{ borderColor: 'rgba(217, 158, 40, 0.15)' }}>
                <input
                  type="checkbox"
                  checked={autoRunAfterRefine}
                  onChange={(e) => setAutoRunAfterRefine(e.target.checked)}
                  className="accent-amber-500"
                />
                <span className="text-white/70">Auto-run after refine</span>
              </label>
            </div>
          </div>
        </div>

        {/* Artifact Browser Card */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
            style={{ 
              borderColor: 'rgba(217, 158, 40, 0.15)', 
              background: 'rgba(15, 15, 24, 0.6)',
              boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
            }}>
          <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
              style={{
                background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer 3s infinite'
              }} />
          
          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                <span className="text-amber-400 text-sm">📁</span>
              </div>
              <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                Artifact Browser
              </div>
            </div>
            
            <div className="text-[10px] uppercase tracking-widest mb-3 font-medium px-3 py-2 rounded-lg"
                style={{ 
                  color: 'rgba(217, 158, 40, 0.8)',
                  background: 'rgba(217, 158, 40, 0.05)',
                  borderLeft: '3px solid rgba(217, 158, 40, 0.4)'
                }}>
              /artifacts/versions/{selectedVersion}{artifactPath ? `/${artifactPath}` : ''}
            </div>
            
            <div className="space-y-2 max-h-[240px] overflow-auto border rounded-xl p-3 custom-scrollbar"
                style={{ 
                  borderColor: 'rgba(217, 158, 40, 0.15)',
                  background: 'rgba(0, 0, 0, 0.3)'
                }}>
              {artifactPath && (
                <div className="flex items-center justify-between text-sm rounded-lg p-3 cursor-pointer transition-all duration-200"
                    style={{ background: 'rgba(217, 158, 40, 0.05)' }}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.background = 'rgba(217, 158, 40, 0.12)';
                      e.currentTarget.style.transform = 'translateX(4px)';
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)';
                      e.currentTarget.style.transform = 'translateX(0)';
                    }}
                    onClick={upArtifact}>
                  <span className="text-white/90 flex items-center gap-2">
                    <span className="text-amber-400">↩️</span> ..
                  </span>
                  <span className="text-white/50 text-xs">Up</span>
                </div>
              )}
              {artifactList.length ? (
                artifactList.map((it) => (
                  <div
                    key={it.name}
                    className="flex items-center justify-between text-sm rounded-lg p-3 cursor-pointer transition-all duration-200"
                    style={{ background: 'rgba(217, 158, 40, 0.05)' }}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.background = 'rgba(217, 158, 40, 0.12)';
                      e.currentTarget.style.transform = 'translateX(4px)';
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)';
                      e.currentTarget.style.transform = 'translateX(0)';
                    }}
                    onClick={() => onArtifactItemClick(it)}
                  >
                    <span className={`${it.isDir ? 'text-amber-300 hover:text-amber-200' : 'text-white/80'} transition-colors flex items-center gap-2`}>
                      <span className="text-amber-400/70">{it.isDir ? '📁' : '📄'}</span>
                      {it.name.replace(/\/$/, '')}
                    </span>
                    <div className="flex items-center gap-2">
                      {!it.isDir && (
                        <Btn variant="ghost" onClick={(e) => { e.stopPropagation(); viewArtifactFile(it.name); }}>
                          👁️ View
                        </Btn>
                      )}
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-white/60 text-sm text-center py-6">
                  <div className="text-2xl mb-2 opacity-30">📭</div>
                  This folder is empty.
                </div>
              )}
            </div>
            
            <div className="mt-3 flex items-center gap-2">
              <Btn variant="ghost" onClick={loadArtifactFiles}>
                🔄 Refresh
              </Btn>
            </div>
            
            {/* Generate Driver Section */}
            <div className="mt-5 pt-5 border-t" style={{ borderColor: 'rgba(217, 158, 40, 0.15)' }}>
              <div className="text-sm font-semibold mb-3 flex items-center gap-2">
                <span className="text-amber-400">⚡</span>
                <span className="text-amber-300/90">Generate Driver & Tests</span>
              </div>
              <div className="space-y-3">
                <textarea
                  className="w-full rounded-xl border px-4 py-3 text-sm text-white placeholder-white/40 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)] resize-none"
                  style={{ 
                    background: 'rgba(0, 0, 0, 0.4)', 
                    borderColor: 'rgba(217, 158, 40, 0.2)'
                  }}
                  placeholder="Enter brief description for generation (e.g., Generate driver and tests for cat vs dog classifier…)"
                  value={briefInput}
                  onChange={(e) => setBriefInput(e.target.value)}
                  rows={3}
                />
                <Btn 
                  variant="primary" 
                  className="w-full"
                  onClick={handleGenerateDriverAndTests}
                  disabled={generatingFiles || !briefInput.trim()}
                >
                  {generatingFiles ? "⏳ Generating..." : "⚡ Generate Driver & Tests"}
                </Btn>
              </div>
            </div>
          </div>
        </div>

        {/* Recent Feedback Card */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
            style={{ 
              borderColor: 'rgba(217, 158, 40, 0.15)', 
              background: 'rgba(15, 15, 24, 0.6)',
              boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
            }}>
          <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
              style={{
                background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer 3s infinite'
              }} />
          
          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                <span className="text-amber-400 text-sm">💬</span>
              </div>
              <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                Recent Feedback
              </div>
            </div>
            
            <div className="space-y-2 max-h-[280px] overflow-auto border rounded-xl p-3 custom-scrollbar"
                style={{ 
                  borderColor: 'rgba(217, 158, 40, 0.15)',
                  background: 'rgba(0, 0, 0, 0.3)'
                }}>
              {prompts?.length ? (
                prompts.map((p) => (
                  <div key={p.id || p.createdAt || p.message} 
                      className="text-sm rounded-lg p-3 transition-all duration-200 border border-transparent hover:border-amber-400/20"
                      style={{ background: 'rgba(217, 158, 40, 0.05)' }}
                      onMouseEnter={(e) => {
                        e.currentTarget.style.background = 'rgba(217, 158, 40, 0.1)';
                        e.currentTarget.style.transform = 'translateY(-2px)';
                      }}
                      onMouseLeave={(e) => {
                        e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)';
                        e.currentTarget.style.transform = 'translateY(0)';
                      }}>
                    <div className="text-white/90 leading-relaxed">{p.message}</div>
                    <div className="text-[10px] mt-2 font-medium flex items-center gap-2"
                        style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
                      <span>⏱️</span>
                      <span>
                        {p.createdAt ? new Date(p.createdAt).toLocaleString() : ""}
                        {p.runId ? ` • run_${p.runId}` : ""}
                      </span>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-white/60 text-sm text-center py-6">
                  <div className="text-2xl mb-2 opacity-30">💭</div>
                  No prompts yet.
                </div>
              )}
            </div>
            
            <div className="mt-3">
              <Btn variant="ghost" onClick={refreshPrompts}>
                🔄 Refresh
              </Btn>
            </div>
          </div>
        </div>

        {/* Console Card */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
            style={{ 
              borderColor: 'rgba(217, 158, 40, 0.15)', 
              background: 'rgba(15, 15, 24, 0.6)',
              boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
            }}>
          <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
              style={{
                background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer 3s infinite'
              }} />
          
          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                <span className="text-amber-400 text-sm">🖥️</span>
              </div>
              <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                Console
              </div>
            </div>
            
            <div className="rounded-xl p-4 border relative overflow-hidden"
                style={{ 
                  background: 'rgba(0, 0, 0, 0.5)',
                  borderColor: 'rgba(217, 158, 40, 0.2)'
                }}>
              <div className="absolute top-2 right-2 flex gap-1">
                <div className="w-2 h-2 rounded-full bg-red-500/40"></div>
                <div className="w-2 h-2 rounded-full bg-amber-500/40"></div>
                <div className="w-2 h-2 rounded-full bg-green-500/40"></div>
              </div>
              <pre className="text-xs max-h-[260px] overflow-auto font-mono custom-scrollbar pt-4"
                  style={{ color: '#a1a1aa' }}>
      {runId ? consoleLines.join("\n") || "⚡ Streaming…" : "💤 Console appears after run starts"}
              </pre>
            </div>
          </div>
        </div>

        {/* Previous Runs Card */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
            style={{ 
              borderColor: 'rgba(217, 158, 40, 0.15)', 
              background: 'rgba(15, 15, 24, 0.6)',
              boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
            }}>
          <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
              style={{
                background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                backgroundSize: '200% 100%',
                animation: 'shimmer 3s infinite'
              }} />
          
          <div className="relative z-10">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                  <span className="text-amber-400 text-sm">📊</span>
                </div>
                <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                  Previous Runs
                </div>
              </div>
              <Btn variant="ghost" onClick={refreshRuns}>
                🔄 Refresh
              </Btn>
            </div>
            
            <div className="text-[10px] uppercase tracking-widest mb-3 font-medium px-3 py-2 rounded-lg"
                style={{ 
                  color: 'rgba(217, 158, 40, 0.8)',
                  background: 'rgba(217, 158, 40, 0.05)'
                }}>
              📈 Accuracy trend (recent {trend.length || 0})
            </div>
            
            {trend.length ? (
              <div className="h-28 mb-4 rounded-xl overflow-hidden border p-2"
                  style={{ 
                    background: 'rgba(0, 0, 0, 0.3)',
                    borderColor: 'rgba(217, 158, 40, 0.15)'
                  }}>
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={trend.map((d, i) => ({ i, acc: d.acc }))}>
                    <XAxis dataKey="i" hide />
                    <YAxis domain={[0, 1]} hide />
                    <Tooltip
                      formatter={(v) => (v == null ? "—" : Number(v).toFixed(4))}
                      labelFormatter={(i) => `Run ${trend[i]?.runId ?? ""}`}
                      contentStyle={{ 
                        background: 'rgba(0, 0, 0, 0.95)', 
                        border: '1px solid rgba(217, 158, 40, 0.3)',
                        borderRadius: '8px',
                        padding: '8px 12px'
                      }}
                      labelStyle={{ color: '#d4af37' }}
                    />
                    <Line 
                      type="monotone" 
                      dataKey="acc" 
                      stroke="rgb(217, 158, 40)" 
                      strokeWidth={2.5} 
                      dot={{ fill: 'rgb(217, 158, 40)', r: 4, strokeWidth: 2, stroke: 'rgba(0,0,0,0.5)' }}
                      activeDot={{ r: 6 }}
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <div className="text-white/50 text-sm mb-4 text-center py-6 rounded-xl border"
                  style={{ 
                    background: 'rgba(0, 0, 0, 0.3)',
                    borderColor: 'rgba(217, 158, 40, 0.1)'
                  }}>
                <div className="text-2xl mb-2 opacity-30">📈</div>
                No trend yet.
              </div>
            )}
            
            {runs.length ? (
              <div className="flex flex-wrap gap-2">
                {runs.map((r) => (
                  <Btn
                    key={r.runId}
                    className={`px-4 py-2 transition-all duration-300 ${
                      selectedRunId === r.runId 
                        ? "shadow-lg" 
                        : "hover:scale-105"
                    }`}
                    style={selectedRunId === r.runId ? {
                      borderColor: 'rgba(217, 158, 40, 0.6)',
                      background: 'rgba(217, 158, 40, 0.15)',
                      color: '#d4af37',
                      boxShadow: '0 0 20px rgba(217, 158, 40, 0.3)'
                    } : {}}
                    onClick={() => setSelectedRunId(r.runId)}
                  >
                    🏃 run_{r.runId}
                  </Btn>
                ))}
              </div>
            ) : (
              <div className="text-white/60 text-sm text-center py-6 rounded-xl border"
                  style={{ 
                    background: 'rgba(0, 0, 0, 0.3)',
                    borderColor: 'rgba(217, 158, 40, 0.1)'
                  }}>
                <div className="text-2xl mb-2 opacity-30">🏃</div>
                No previous runs yet.
              </div>
            )}
          </div>
        </div>

        <style jsx>{`
          @keyframes shimmer {
            0% { background-position: -200% 0; }
            100% { background-position: 200% 0; }
          }
          .custom-scrollbar::-webkit-scrollbar {
            width: 6px;
          }
          .custom-scrollbar::-webkit-scrollbar-track {
            background: rgba(0, 0, 0, 0.2);
            border-radius: 10px;
          }
          .custom-scrollbar::-webkit-scrollbar-thumb {
            background: rgba(217, 158, 40, 0.3);
            border-radius: 10px;
          }
          .custom-scrollbar::-webkit-scrollbar-thumb:hover {
            background: rgba(217, 158, 40, 0.5);
          }
        `}</style>
      </div>

        {/* right */}
        <div className="space-y-6">
          {/* Viewing Artifact Card */}
          {viewingArtifact && (
            <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
                style={{ 
                  borderColor: 'rgba(217, 158, 40, 0.15)', 
                  background: 'rgba(15, 15, 24, 0.6)',
                  boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
                }}>
              <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
                  style={{
                    background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                    backgroundSize: '200% 100%',
                    animation: 'shimmer 3s infinite'
                  }} />
              
              <div className="relative z-10">
                <div className="flex justify-between items-center mb-4">
                  <div>
                    <div className="flex items-center gap-3 mb-2">
                      <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                        <span className="text-amber-400 text-sm">👁️</span>
                      </div>
                      <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                        Viewing: {viewingArtifact}
                      </div>
                    </div>
                    <div className="text-xs px-3 py-1 rounded-lg inline-block"
                        style={{ 
                          color: 'rgba(217, 158, 40, 0.8)',
                          background: 'rgba(217, 158, 40, 0.05)',
                          border: '1px solid rgba(217, 158, 40, 0.2)'
                        }}>
                      📦 Artifact from {selectedVersion}
                    </div>
                  </div>
                  <Btn variant="ghost" onClick={() => setViewingArtifact(null)}>
                    ✕ Close
                  </Btn>
                </div>
                <div className="rounded-xl overflow-hidden border"
                    style={{ borderColor: 'rgba(217, 158, 40, 0.2)' }}>
                  <Editor
                    height="300px"
                    language={ext(viewingArtifact) === "py" ? "python" : ext(viewingArtifact) === "yaml" ? "yaml" : "plaintext"}
                    theme="vs-dark"
                    value={artifactContent}
                    options={{ readOnly: true, minimap: { enabled: false }, fontSize: 14 }}
                  />
                </div>
              </div>
            </div>
          )}

          {/* Project Files Card */}
          <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.15)', 
                background: 'rgba(15, 15, 24, 0.6)',
                boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
              }}>
            <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
                style={{
                  background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                  backgroundSize: '200% 100%',
                  animation: 'shimmer 3s infinite'
                }} />
            
            <div className="relative z-10">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                  <span className="text-amber-400 text-sm">📂</span>
                </div>
                <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                  Project Files (pre-processed)
                </div>
              </div>
              
              {/* File Tabs */}
              <div className="flex gap-2 mb-4 pb-3 border-b overflow-x-auto custom-scrollbar"
                  style={{ borderColor: 'rgba(217, 158, 40, 0.15)' }}>
                {readyForEditor &&
                  files.map((f) => (
                    <button
                      key={f.name}
                      onClick={() => setActiveFile(f.name)}
                      className={`px-4 py-2.5 text-sm rounded-lg transition-all duration-300 whitespace-nowrap font-medium ${
                        activeFile === f.name 
                          ? "shadow-lg scale-105" 
                          : "hover:bg-amber-500/5"
                      }`}
                      style={activeFile === f.name ? {
                        borderWidth: '2px',
                        borderStyle: 'solid',
                        borderColor: 'rgb(217, 158, 40)',
                        background: 'rgba(217, 158, 40, 0.15)',
                        color: '#d4af37',
                        boxShadow: '0 0 20px rgba(217, 158, 40, 0.3)'
                      } : {
                        border: '1px solid rgba(217, 158, 40, 0.15)',
                        color: 'rgba(255, 255, 255, 0.6)'
                      }}
                    >
                      {f.name}
                    </button>
                  ))}
                {!readyForEditor && (
                  <div className="text-xs px-4 py-3 rounded-lg border"
                      style={{ 
                        color: 'rgba(255, 255, 255, 0.7)',
                        background: 'rgba(217, 158, 40, 0.05)',
                        borderColor: 'rgba(217, 158, 40, 0.2)'
                      }}>
                    📝 <b className="text-amber-300">driver.py</b> & <b className="text-amber-300">tests.yaml</b> not ready. 
                    Click <b className="text-amber-300">Generate driver + tests.yaml</b>.
                  </div>
                )}
              </div>

              {/* Editor */}
              {readyForEditor && activeFile && (
                <div className="rounded-xl overflow-hidden border mb-4"
                    style={{ borderColor: 'rgba(217, 158, 40, 0.2)' }}>
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
                </div>
              )}

              {readyForEditor && (
                <div className="flex gap-3 mb-5">
                  <Btn onClick={saveAndApprove} disabled={busy || !projectName}>
                    💾 Save & Approve
                  </Btn>
                  <Btn variant="primary" onClick={approveAndRun} disabled={busy || !projectName}>
                    {busy ? "⏳ Starting…" : "▶️ Approve & Run"}
                  </Btn>
                  <Btn variant="ghost" onClick={loadEditorsFromPreProcessed} disabled={busy || !projectName}>
                    🔄 Reload from S3
                  </Btn>
                </div>
              )}

              {/* Console Section */}
              <div className="mt-5 pt-5 border-t" style={{ borderColor: 'rgba(217, 158, 40, 0.15)' }}>
                <div className="flex items-center gap-3 mb-3">
                  <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                    <span className="text-amber-400 text-xs">🖥️</span>
                  </div>
                  <div className="text-sm font-semibold text-amber-300/90">Console Output</div>
                </div>
                
                <div className="rounded-xl p-4 border relative overflow-hidden"
                    style={{ 
                      background: 'rgba(0, 0, 0, 0.5)',
                      borderColor: 'rgba(217, 158, 40, 0.2)'
                    }}>
                  <div className="absolute top-3 right-3 flex gap-1.5">
                    <div className="w-2.5 h-2.5 rounded-full bg-red-500/50 shadow-lg shadow-red-500/20"></div>
                    <div className="w-2.5 h-2.5 rounded-full bg-amber-500/50 shadow-lg shadow-amber-500/20"></div>
                    <div className="w-2.5 h-2.5 rounded-full bg-green-500/50 shadow-lg shadow-green-500/20"></div>
                  </div>
                  <pre className="text-xs max-h-[300px] overflow-auto font-mono custom-scrollbar pt-6"
                      style={{ color: '#a1a1aa' }}>
        {runId ? consoleLines.join("\n") || "⚡ Streaming…" : "💤 Console appears after run starts"}
                  </pre>
                </div>
              </div>
            </div>
          </div>

          {/* Results Card */}
          <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.15)', 
                background: 'rgba(15, 15, 24, 0.6)',
                boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
              }}>
            <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
                style={{
                  background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
                  backgroundSize: '200% 100%',
                  animation: 'shimmer 3s infinite'
                }} />
            
            <div className="relative z-10">
              <div className="flex items-center gap-3 mb-6">
                <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                  <span className="text-amber-400 text-sm">📊</span>
                </div>
                <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                  Results Dashboard
                </div>
              </div>

              {/* Metrics Grid */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-5 mb-6">
                {/* Metrics Chart */}
                <div className="rounded-xl p-4 border"
                    style={{ 
                      background: 'rgba(0, 0, 0, 0.3)',
                      borderColor: 'rgba(217, 158, 40, 0.15)'
                    }}>
                  <div className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <span className="text-amber-400">📈</span>
                    <span className="text-amber-300/90">Metrics</span>
                  </div>
                  {metrics ? (
                    <ResponsiveContainer width="100%" height={200}>
                      <LineChart
                        data={Object.entries(metrics).map(([k, v]) => ({ name: k, value: typeof v === "number" ? v : null }))}
                      >
                        <CartesianGrid strokeDasharray="3 3" stroke="rgba(217, 158, 40, 0.1)" />
                        <XAxis dataKey="name" stroke="#d4af37" fontSize={11} />
                        <YAxis stroke="#d4af37" fontSize={11} />
                        <Tooltip 
                          formatter={(v) => (v == null ? "—" : Number(v).toFixed(4))}
                          contentStyle={{ 
                            background: 'rgba(0, 0, 0, 0.95)', 
                            border: '1px solid rgba(217, 158, 40, 0.3)',
                            borderRadius: '8px'
                          }}
                          labelStyle={{ color: '#d4af37' }}
                        />
                        <Line type="monotone" dataKey="value" stroke="rgb(217, 158, 40)" strokeWidth={2.5} dot={{ fill: 'rgb(217, 158, 40)', r: 4 }} />
                      </LineChart>
                    </ResponsiveContainer>
                  ) : (
                    <div className="text-white/60 text-sm text-center py-12">
                      <div className="text-2xl mb-2 opacity-30">📊</div>
                      No metrics yet.
                    </div>
                  )}
                </div>

                {/* Confusion Matrix */}
                <div className="rounded-xl p-4 border"
                    style={{ 
                      background: 'rgba(0, 0, 0, 0.3)',
                      borderColor: 'rgba(217, 158, 40, 0.15)'
                    }}>
                  <div className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <span className="text-amber-400">🎯</span>
                    <span className="text-amber-300/90">Confusion Matrix</span>
                  </div>
                  {confusionURL ? (
                    <img src={confusionURL} alt="Confusion" className="max-h-60 w-full object-contain rounded-lg" />
                  ) : (
                    <div className="text-white/60 text-sm text-center py-12">
                      <div className="text-2xl mb-2 opacity-30">🎯</div>
                      No confusion matrix.
                    </div>
                  )}
                </div>

                {/* Pass/Fail Chart */}
                <div className="rounded-xl p-4 border"
                    style={{ 
                      background: 'rgba(0, 0, 0, 0.3)',
                      borderColor: 'rgba(217, 158, 40, 0.15)'
                    }}>
                  <div className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <span className="text-amber-400">✓✗</span>
                    <span className="text-amber-300/90">Pass/Fail</span>
                  </div>
                  {(() => {
                    const statusIdx = csvInfo.headers.findIndex((h) => {
                      const hl = (h || "").toLowerCase();
                      return hl === "status" || hl === "result";
                    });
                    if (statusIdx === -1) return (
                      <div className="text-white/60 text-sm text-center py-12">
                        <div className="text-2xl mb-2 opacity-30">📋</div>
                        No summary.
                      </div>
                    );
                    let pass = 0, fail = 0;
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
                          <Tooltip 
                            contentStyle={{ 
                              background: 'rgba(0, 0, 0, 0.95)', 
                              border: '1px solid rgba(217, 158, 40, 0.3)',
                              borderRadius: '8px'
                            }}
                          />
                        </PieChart>
                      </ResponsiveContainer>
                    );
                  })()}
                </div>
              </div>

              {/* CSV Table */}
              <div className="rounded-xl border p-4"
                  style={{ 
                    background: 'rgba(0, 0, 0, 0.3)',
                    borderColor: 'rgba(217, 158, 40, 0.15)'
                  }}>
                <div className="flex items-center gap-3 mb-4">
                  <Input
                    placeholder="🔍 Search scenarios…"
                    value={csvSearch}
                    onChange={(e) => {
                      setCsvSearch(e.target.value);
                      setCsvPage(1);
                    }}
                    className="flex-1 max-w-sm bg-transparent border rounded-xl px-4 py-2.5 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
                    style={{ 
                      borderColor: 'rgba(217, 158, 40, 0.2)',
                      background: 'rgba(0, 0, 0, 0.3)',
                      color: 'white'
                    }}
                  />
                  <div className="ml-auto text-xs font-medium px-3 py-2 rounded-lg"
                      style={{ 
                        color: 'rgba(217, 158, 40, 0.8)',
                        background: 'rgba(217, 158, 40, 0.05)',
                        border: '1px solid rgba(217, 158, 40, 0.2)'
                      }}>
                    📄 Page {csvPage} / {Math.max(1, Math.ceil(filteredRows.length / pageSize))}
                  </div>
                </div>
                
                <div className="overflow-auto border rounded-xl custom-scrollbar"
                    style={{ borderColor: 'rgba(217, 158, 40, 0.15)' }}>
                  <table className="min-w-full text-sm">
                    <thead style={{ background: 'rgba(217, 158, 40, 0.05)' }}>
                      <tr>
                        {csvInfo.headers.map((h, i) => (
                          <th key={i} 
                              className="px-4 py-3 text-left font-semibold border-b"
                              style={{ 
                                color: 'rgba(217, 158, 40, 0.9)',
                                borderColor: 'rgba(217, 158, 40, 0.2)'
                              }}>
                            {h}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {pageRows.map((row, rIdx) => {
                        const resultIdx = csvInfo.headers.findIndex((h) => (h || "").toLowerCase() === "result");
                        return (
                          <tr key={rIdx} 
                              className="transition-colors duration-150"
                              style={{ 
                                background: rIdx % 2 === 0 ? 'transparent' : 'rgba(217, 158, 40, 0.03)'
                              }}
                              onMouseEnter={(e) => e.currentTarget.style.background = 'rgba(217, 158, 40, 0.08)'}
                              onMouseLeave={(e) => e.currentTarget.style.background = rIdx % 2 === 0 ? 'transparent' : 'rgba(217, 158, 40, 0.03)'}>
                            {row.map((cell, cIdx) => {
                              const base = "px-4 py-3 border-b";
                              if (cIdx === resultIdx) {
                                const v = String(cell || "").toLowerCase();
                                const cls = v.includes("pass")
                                  ? "text-emerald-400 font-semibold"
                                  : v.includes("fail")
                                  ? "text-rose-400 font-semibold"
                                  : "text-white/80";
                                return (
                                  <td key={cIdx} className={`${base} ${cls}`}
                                      style={{ borderColor: 'rgba(217, 158, 40, 0.1)' }}>
                                    {v.includes("pass") && "✓ "}{v.includes("fail") && "✗ "}{cell}
                                  </td>
                                );
                              }
                              return (
                                <td key={cIdx} className={`${base} text-white/80`}
                                    style={{ borderColor: 'rgba(217, 158, 40, 0.1)' }}>
                                  {cell}
                                </td>
                              );
                            })}
                          </tr>
                        );
                      })}
                      {!pageRows.length && (
                        <tr>
                          <td colSpan={csvInfo.headers.length || 1} className="px-4 py-12 text-center">
                            <div className="text-white/60">
                              <div className="text-2xl mb-2 opacity-30">📋</div>
                              No rows found
                            </div>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
                
                <div className="flex gap-2 mt-4">
                  <Btn onClick={() => setCsvPage((p) => Math.max(1, p - 1))} disabled={csvPage <= 1}>
                    ← Prev
                  </Btn>
                  <Btn
                    onClick={() => setCsvPage((p) => Math.min(Math.ceil(filteredRows.length / pageSize), p + 1))}
                    disabled={csvPage >= Math.ceil(filteredRows.length / pageSize)}
                  >
                    Next →
                  </Btn>
                </div>
              </div>
            </div>
          </div>

          <style jsx>{`
            @keyframes shimmer {
              0% { background-position: -200% 0; }
              100% { background-position: 200% 0; }
            }
            .custom-scrollbar::-webkit-scrollbar {
              width: 6px;
              height: 6px;
            }
            .custom-scrollbar::-webkit-scrollbar-track {
              background: rgba(0, 0, 0, 0.2);
              border-radius: 10px;
            }
            .custom-scrollbar::-webkit-scrollbar-thumb {
              background: rgba(217, 158, 40, 0.3);
              border-radius: 10px;
            }
            .custom-scrollbar::-webkit-scrollbar-thumb:hover {
              background: rgba(217, 158, 40, 0.5);
            }
          `}</style>
        </div>
      </div>
    </div>
  );
}
