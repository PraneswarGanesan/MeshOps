// src/pages/Retrain.jsx
import React, { useEffect, useState } from "react";
import Editor from "@monaco-editor/react";
import { startRetrainJob, getRetrainStatus, getRetrainConsole, buildSaveBase } from "../api/retrain";
import { StorageAPI } from "../api/storage";
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from "recharts";

/* ---------- small UI atoms ---------- */
const Btn = ({ className = "", variant = "default", ...p }) => {
  const variants = {
    default: "border-white/15 hover:bg-white/10",
    primary:
      "border-transparent bg-gradient-to-b from-[#D4AF37]/80 to-[#B69121]/80 hover:from-[#D4AF37] hover:to-[#B69121] text-black font-medium shadow-lg shadow-yellow-800/20",
    ghost: "border-transparent hover:bg-white/5",
    danger: "border-transparent bg-gradient-to-b from-red-600/80 to-red-700/80 hover:from-red-600 hover:to-red-700 text-white font-medium",
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
const TEXT_EXT = new Set(["py", "yaml", "yml", "json", "js", "ts", "txt", "md", "csv"]);
const ext = (n = "") => (n.includes(".") ? n.split(".").pop().toLowerCase() : "");

export default function Retrain() {
  const username = localStorage.getItem("username") || "pg";

  // state
  const [projects, setProjects] = useState([]);
  const [projectName, setProjectName] = useState(localStorage.getItem("activeProject") || "");
  const [versions, setVersions] = useState([]);
  const [selectedVersion, setSelectedVersion] = useState("v1");
  const [availableFiles, setAvailableFiles] = useState([]);
  const [selectedFiles, setSelectedFiles] = useState([]);
  const [requirementsFile, setRequirementsFile] = useState("");
  const [customRequirements, setCustomRequirements] = useState("");
  const [jobId, setJobId] = useState(null);
  const [jobStatus, setJobStatus] = useState(null);
  const [consoleOutput, setConsoleOutput] = useState("");
  const [report, setReport] = useState(null);
  const [busy, setBusy] = useState(false);
  const [jobs, setJobs] = useState([]);

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

  /* load available files for training */
  const loadAvailableFiles = async () => {
    if (!projectName) return;
    try {
      const preProcessedFiles = await StorageAPI.listFiles(username, projectName, "pre-processed");
      const artifactFiles = await StorageAPI.listFiles(username, projectName, `artifacts/versions/${selectedVersion}`);
      
      const allFiles = [
        ...preProcessedFiles.map(f => ({ name: f, source: "pre-processed" })),
        ...artifactFiles.filter(f => !f.endsWith("/")).map(f => ({ name: f, source: "artifacts" }))
      ];
      
      // Filter for relevant training files
      const trainingFiles = allFiles.filter(f => {
        const extension = ext(f.name);
        return TEXT_EXT.has(extension) || extension === "csv" || f.name.includes("train") || f.name.includes("data");
      });
      
      setAvailableFiles(trainingFiles);
      
      // Auto-select common training files
      const autoSelect = trainingFiles.filter(f => 
        f.name.includes("train") || f.name.includes("data") || f.name === "dataset.csv"
      );
      setSelectedFiles(autoSelect.map(f => f.name));
      
      // Auto-detect requirements file
      const reqFile = trainingFiles.find(f => f.name.endsWith("requirements.txt"));
      if (reqFile) {
        if (reqFile.source === "pre-processed") {
          setRequirementsFile(`${username}/${projectName}/pre-processed/requirements.txt`);
        } else {
          setRequirementsFile(`${username}/${projectName}/artifacts/versions/${selectedVersion}/requirements.txt`);
        }
      }
    } catch (error) {
      console.error("Error loading files:", error);
      setAvailableFiles([]);
    }
  };

  useEffect(() => {
    if (projectName) {
      loadVersions();
    }
  }, [projectName]);

  useEffect(() => {
    if (projectName && selectedVersion) {
      loadAvailableFiles();
    }
  }, [projectName, selectedVersion]);

  /* start retrain job */
  const startRetrain = async () => {
    if (!projectName || selectedFiles.length === 0) {
      alert("Please select at least one training file");
      return;
    }

    setBusy(true);
    try {
      const saveBase = buildSaveBase(username, projectName, selectedVersion);
      // Build S3 paths per file source
      const nameToSource = new Map(availableFiles.map(f => [f.name, f.source]));
      const filePaths = selectedFiles.map(name => {
        const src = nameToSource.get(name);
        if (src === "artifacts") {
          return `${username}/${projectName}/artifacts/versions/${selectedVersion}/${name}`;
        }
        return `${username}/${projectName}/pre-processed/${name}`;
      });
      
      const result = await startRetrainJob({
        username,
        projectName,
        files: filePaths,
        saveBase,
        version: selectedVersion,
        requirementsPath: requirementsFile || null,
        extraArgs: []
      });

      setJobId(result.jobId);
      setJobStatus(result.status);
      setJobs(prev => [{ jobId: result.jobId, status: result.status, createdAt: new Date().toISOString() }, ...prev]);
      
    } catch (error) {
      alert("Failed to start retrain job: " + error.message);
    } finally {
      setBusy(false);
    }
  };

  /* poll job status */
  useEffect(() => {
    if (!jobId) return;
    
    const interval = setInterval(async () => {
      try {
        const status = await getRetrainStatus(jobId);
        setJobStatus(status.status);
        setReport(status.report);
        
        if (status.status === "COMPLETED" || status.status === "FAILED") {
          clearInterval(interval);
        }
      } catch (error) {
        console.error("Error polling status:", error);
      }
    }, 3000);

    return () => clearInterval(interval);
  }, [jobId]);

  /* poll console output */
  useEffect(() => {
    if (!jobId || jobStatus === "COMPLETED" || jobStatus === "FAILED") return;
    
    const interval = setInterval(async () => {
      try {
        const console = await getRetrainConsole(jobId);
        setConsoleOutput(console.stdout || "");
      } catch (error) {
        console.error("Error polling console:", error);
      }
    }, 3000);

    return () => clearInterval(interval);
  }, [jobId, jobStatus]);

  /* file selection handlers */
  const toggleFileSelection = (fileName) => {
    setSelectedFiles(prev => 
      prev.includes(fileName) 
        ? prev.filter(f => f !== fileName)
        : [...prev, fileName]
    );
  };

  const selectAllFiles = () => {
    setSelectedFiles(availableFiles.map(f => f.name));
  };

  const clearSelection = () => {
    setSelectedFiles([]);
  };

  return (
    <div className="min-h-screen text-white relative">
      {/* header */}
      <div className="sticky top-0 z-20 bg-[#0B0B1A]/80 backdrop-blur border-b border-white/10 px-5 py-4 flex items-center justify-between">
        <div>
          <div className="text-xs text-white/60">ML Pipeline</div>
          <div className="text-xl font-semibold">Model Retraining</div>
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
          <Btn variant="primary" onClick={startRetrain} disabled={busy || selectedFiles.length === 0}>
            {busy ? "Starting..." : "Start Retraining"}
          </Btn>
        </div>
      </div>

      {/* main content */}
      <div className="max-w-[1400px] mx-auto p-5 grid grid-cols-1 lg:grid-cols-[400px_minmax(0,1fr)] gap-5">
        {/* left panel */}
        <div className="space-y-5">
          <Card title="Training Configuration">
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-2">Requirements File (Optional)</label>
                <Input
                  value={requirementsFile}
                  onChange={(e) => setRequirementsFile(e.target.value)}
                  placeholder="S3 path to requirements.txt"
                />
              </div>
              
              <div>
                <label className="block text-sm font-medium mb-2">Custom Requirements</label>
                <textarea
                  value={customRequirements}
                  onChange={(e) => setCustomRequirements(e.target.value)}
                  rows={3}
                  placeholder="Additional pip packages (one per line)"
                  className="w-full bg-[#121235] border border-white/10 rounded px-3 py-2 text-sm text-white placeholder-white/40"
                />
              </div>
            </div>
          </Card>

          <Card title="Training Files">
            <div className="space-y-3">
              <div className="flex gap-2">
                <Btn variant="ghost" onClick={selectAllFiles}>
                  Select All
                </Btn>
                <Btn variant="ghost" onClick={clearSelection}>
                  Clear
                </Btn>
              </div>
              
              <div className="max-h-[300px] overflow-auto space-y-3">
                {(() => {
                  const groups = availableFiles.reduce((acc, f) => {
                    const top = f.name.includes('/') ? f.name.split('/')[0] : 'root';
                    if (!acc[top]) acc[top] = [];
                    acc[top].push(f);
                    return acc;
                  }, {});
                  return Object.entries(groups).map(([group, files]) => {
                    const allSelected = files.every(f => selectedFiles.includes(f.name));
                    const someSelected = !allSelected && files.some(f => selectedFiles.includes(f.name));
                    return (
                      <div key={group} className="border border-white/10 rounded">
                        <div className="flex items-center gap-2 px-2 py-2 bg-white/5">
                          <input
                            type="checkbox"
                            checked={allSelected}
                            ref={el => { if (el) el.indeterminate = someSelected; }}
                            onChange={(e) => {
                              const checked = e.target.checked;
                              setSelectedFiles(prev => {
                                const names = new Set(prev);
                                files.forEach(f => { if (checked) names.add(f.name); else names.delete(f.name); });
                                return Array.from(names);
                              });
                            }}
                          />
                          <div className="text-sm font-medium text-white/80">{group}</div>
                        </div>
                        <div className="space-y-2 p-2">
                          {files.map(file => (
                            <div key={file.name} className="flex items-center gap-2 p-2 bg-white/5 rounded">
                              <input
                                type="checkbox"
                                checked={selectedFiles.includes(file.name)}
                                onChange={() => toggleFileSelection(file.name)}
                                className="rounded"
                              />
                              <div className="flex-1">
                                <div className="text-sm text-white/80">{file.name}</div>
                                <div className="text-xs text-white/50">{file.source}</div>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    );
                  });
                })()}
              </div>
              
              <div className="text-xs text-white/60">
                Selected: {selectedFiles.length} files
              </div>
            </div>
          </Card>

          <Card title="Console Output">
            <pre className="bg-black/30 rounded p-3 text-xs max-h-[300px] overflow-auto whitespace-pre-wrap">
              {jobId ? (consoleOutput || "Waiting for output...") : "Start a retrain job to see console output"}
            </pre>
          </Card>
        </div>

        {/* right panel */}
        <div className="space-y-5">
          <Card title="Job Status">
            {jobId ? (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-white/60">Job ID:</span>
                  <span className="text-sm font-mono">{jobId}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-sm text-white/60">Status:</span>
                  <span className={`text-sm font-medium ${
                    jobStatus === "COMPLETED" ? "text-green-400" :
                    jobStatus === "FAILED" ? "text-red-400" :
                    jobStatus === "RUNNING" ? "text-yellow-400" :
                    "text-blue-400"
                  }`}>
                    {jobStatus}
                  </span>
                </div>
                {report && (
                  <div className="mt-4">
                    <div className="text-sm font-medium mb-2">Training Report:</div>
                    <pre className="bg-black/30 rounded p-3 text-xs max-h-[200px] overflow-auto">
                      {JSON.stringify(report, null, 2)}
                    </pre>
                  </div>
                )}
              </div>
            ) : (
              <div className="text-white/60 text-sm">
                No active job. Start retraining to see status.
              </div>
            )}
          </Card>

          <Card title="Recent Jobs">
            <div className="space-y-2 max-h-[300px] overflow-auto">
              {jobs.length > 0 ? (
                jobs.map((job) => (
                  <div key={job.jobId} className="flex items-center justify-between p-2 bg-white/5 rounded">
                    <div>
                      <div className="text-sm font-mono">{job.jobId.split('_').pop()}</div>
                      <div className="text-xs text-white/50">
                        {new Date(job.createdAt).toLocaleString()}
                      </div>
                    </div>
                    <div className={`text-xs px-2 py-1 rounded ${
                      job.status === "COMPLETED" ? "bg-green-600/20 text-green-400" :
                      job.status === "FAILED" ? "bg-red-600/20 text-red-400" :
                      job.status === "RUNNING" ? "bg-yellow-600/20 text-yellow-400" :
                      "bg-blue-600/20 text-blue-400"
                    }`}>
                      {job.status}
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-white/60 text-sm">No jobs yet.</div>
              )}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
