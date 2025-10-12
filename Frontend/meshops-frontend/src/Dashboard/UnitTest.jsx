// src/pages/UnitTest.jsx
import React, { useEffect, useMemo, useState } from "react";
import Editor from "@monaco-editor/react";
import { 
  ensureUnitTestProject, 
  startUnitTestRun, 
  getUnitTestRunStatus, 
  listUnitTestArtifacts,
  saveUnitTestPrompt,
  listUnitTestPrompts,
  refineUnitTests,
  generateUnitTestsOnly,
  approveUnitTestPlan
} from "../api/unittest";
import { StorageAPI } from "../api/storage";
import {
  PieChart, Pie, Cell, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from "recharts";

/* ---------- endpoints (must match backend) ---------- */
const BASE_RUNS = "http://localhost:8082/api/runs"; // Same as behaviour runs
const BASE_UNIT_TESTS = "http://localhost:8082/api/unit-tests";
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
  let cur = [], val = "", q = false;
  const pushCell = () => { cur.push(val); val = ""; };
  const pushRow = () => { rows.push(cur); cur = []; };
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) {
      if (ch === '"' && text[i + 1] === '"') { val += '"'; i++; }
      else if (ch === '"') { q = false; }
      else { val += ch; }
    } else {
      if (ch === '"') q = true;
      else if (ch === ",") pushCell();
      else if (ch === "\n" || ch === "\r") {
        if (ch === "\r" && text[i + 1] === "\n") i++;
        pushCell(); pushRow();
      } else { val += ch; }
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
  const [unitTestInput, setUnitTestInput] = useState("");
  const [prompts, setPrompts] = useState([]);
  const [autoRunAfterRefine, setAutoRunAfterRefine] = useState(true);
  const [busy, setBusy] = useState(false);
  const [genBusy, setGenBusy] = useState(false);
  const [versions, setVersions] = useState([]);
  const [selectedVersion, setSelectedVersion] = useState("v1");
  const [artifactFiles, setArtifactFiles] = useState([]);
  const [viewingArtifact, setViewingArtifact] = useState(null);
  const [artifactContent, setArtifactContent] = useState("");
  const [artifactPath, setArtifactPath] = useState(""); // path within artifacts/versions/{selectedVersion}
  const [artifactList, setArtifactList] = useState([]);   // [{name,isDir}]

  /* Project bootstrap */
  useEffect(() => {
    (async () => {
      if (!projectName) return;
      try {
        const s3Prefix = `${BUCKET_PREFIX}/${username}/${projectName}/pre-processed`;
        await ensureUnitTestProject({ username, projectName, s3Prefix });
      } catch (e) {
        console.warn("ensureUnitTestProject failed:", e?.message || e);
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

  /* load versions */
  const loadVersions = async () => {
    if (!projectName) return;
    try {
      const items = await StorageAPI.listFiles(username, projectName, "artifacts/versions");
      const versionDirs = (items || [])
        .filter(n => n.endsWith("/") && n.startsWith("v"))
        .map(n => n.slice(0, -1))
        .sort((a, b) => {
          const aNum = parseInt(a.substring(1)) || 0;
          const bNum = parseInt(b.substring(1)) || 0;
          return bNum - aNum;
        });
      setVersions(versionDirs.length ? versionDirs : ["v1"]);
      if (!selectedVersion || !versionDirs.includes(selectedVersion)) {
        setSelectedVersion(versionDirs[0] || "v1");
      }
    } catch {
      setVersions(["v1"]);
      setSelectedVersion("v1");
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

  useEffect(() => {
    if (projectName) {
      loadVersions();
    }
  }, [projectName]);

  useEffect(() => {
    if (projectName && selectedVersion) {
      loadArtifactFiles();
    }
  }, [projectName, selectedVersion]);

  /* view artifact file */
  const viewArtifactFile = async (fileName) => {
    if (!projectName || !selectedVersion) return;
    try {
      const content = await StorageAPI.fetchTextFile(
        username,
        projectName,
        fileName,
        `artifacts/versions/${selectedVersion}`
      );
      setArtifactContent(content || "");
      setViewingArtifact(fileName);
    } catch (e) {
      alert(`Failed to load ${fileName}: ${e.message}`);
    }
  };

  /* Generate unit tests */
  const generateUnitTestsSmart = async () => {
    if (!projectName) return;
    setGenBusy(true);
    try {
      const brief = (unitTestInput || "").trim() || 
        "Generate comprehensive unit tests covering edge cases, boundary conditions, and typical scenarios for all functions and classes in the codebase.";
      await generateUnitTestsOnly(username, projectName, brief);
      setConsoleLines(["Unit tests generated successfully"]);
    } catch (e) {
      alert("Generate failed: " + (e?.message || e));
    } finally {
      setGenBusy(false);
    }
  };

  /* Approve & Run unit tests */
  const approveAndRunUnitTests = async () => {
    if (!projectName) return;
    setBusy(true);
    try {
      await approveUnitTestPlan({ username, projectName });
      const r = await startUnitTestRun({ username, projectName, testType: "unit" });
      const newRunId = r?.data?.runId ?? r?.runId ?? null;
      setRunId(newRunId);
      setConsoleLines(["Starting unit test run…"]);
    } catch (e) {
      alert("Approve & Run failed: " + (e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="min-h-screen text-white relative">
      {/* header */}
      <div className="sticky top-0 z-20 bg-[#0B0B1A]/80 backdrop-blur border-b border-white/10 px-5 py-4 flex items-center justify-between">
        <div>
          <div className="text-xs text-white/60">Testing Suite</div>
          <div className="text-xl font-semibold">Unit Testing</div>
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
          <select
            value={selectedVersion}
            onChange={(e) => setSelectedVersion(e.target.value)}
            className="bg-transparent border px-2 py-1 rounded"
          >
            {versions.map((v) => (
              <option key={v} value={v} className="bg-black">
                {v}
              </option>
            ))}
          </select>
          <Btn onClick={generateUnitTestsSmart} disabled={genBusy || !projectName}>
            {genBusy ? "Generating…" : "Generate Unit Tests"}
          </Btn>
          <Btn variant="primary" onClick={approveAndRunUnitTests} disabled={busy || !projectName}>
            {busy ? "Starting…" : "Approve & Run"}
          </Btn>
        </div>
      </div>

      {/* main split */}
      <div className="max-w-[1400px] mx-auto p-5 grid grid-cols-1 lg:grid-cols-[360px_minmax(0,1fr)] gap-5">
        {/* left */}
        <div className="space-y-5">
          <Card title="Unit Test Creation">
            <textarea
              value={unitTestInput}
              onChange={(e) => setUnitTestInput(e.target.value)}
              rows={6}
              placeholder='Describe unit test requirements (e.g., "Generate comprehensive unit tests for all functions in the ML pipeline with edge cases and boundary conditions...").'
              className="w-full bg-[#121235] border border-white/10 rounded px-3 py-2 text-sm text-white placeholder-white/40"
            />
            <div className="flex flex-wrap items-center gap-3 mt-2">
              <Btn onClick={generateUnitTestsSmart} disabled={genBusy || !unitTestInput.trim()}>
                Generate Unit Tests
              </Btn>
              <Btn variant="primary" onClick={approveAndRunUnitTests} disabled={busy}>
                {busy ? "Running…" : "Run Unit Tests"}
              </Btn>
            </div>
          </Card>

          <Card title="Artifact Files">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                {artifactPath && (
                  <Btn variant="ghost" onClick={upArtifact}>
                    ⬆️ Up
                  </Btn>
                )}
              </div>
              <Btn variant="ghost" onClick={loadVersions}>
                Refresh
              </Btn>
            </div>
            <div className="space-y-2 max-h-[280px] overflow-auto border border-white/10 rounded p-2">
              {artifactList.length ? (
                artifactList.map(({ name, isDir }) => (
                  <div
                    key={name}
                    className="text-sm hover:bg-white/5 rounded p-1 cursor-pointer flex items-center"
                    onClick={() => (isDir ? enterArtifactDir(name) : viewArtifactFile(name))}
                  >
                    <span className="mr-2">{isDir ? "📁" : "📄"}</span>
                    <span className={isDir ? "text-blue-300" : ""}>{name}</span>
                  </div>
                ))
              ) : (
                <div className="text-white/60 text-sm">This folder is empty.</div>
              )}
            </div>
            <div className="mt-2 flex items-center gap-2">
              <Btn variant="ghost" onClick={loadArtifactFiles}>Refresh</Btn>
            </div>
          </Card>

          <Card title="Console">
            <pre className="bg-black/30 rounded p-3 text-xs max-h-[260px] overflow-auto">
              {runId ? consoleLines.join("\n") || "Streaming…" : "Console appears after run starts"}
            </pre>
          </Card>
        </div>

        {/* right */}
        <div className="space-y-5">
          {viewingArtifact && (
            <Card title={`Viewing: ${viewingArtifact}`}>
              <div className="flex justify-between items-center mb-3">
                <div className="text-sm text-white/60">Artifact from {selectedVersion}</div>
                <Btn variant="ghost" onClick={() => setViewingArtifact(null)}>
                  Close
                </Btn>
              </div>
              <Editor
                height="300px"
                language={ext(viewingArtifact) === "py" ? "python" : ext(viewingArtifact) === "yaml" ? "yaml" : "plaintext"}
                theme="vs-dark"
                value={artifactContent}
                options={{ readOnly: true, minimap: { enabled: false }, fontSize: 14 }}
              />
            </Card>
          )}

          <Card title="Unit Test Results">
            <div className="text-white/60 text-sm">
              Unit test execution results and metrics will appear here after running tests.
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
