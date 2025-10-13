import React, { useEffect, useState, useMemo } from "react";
import Editor from "@monaco-editor/react";
import { StorageAPI } from "../api/storage";

/* ---------------- Theme ---------------- */
const COLORS = {
  bg: "#0B0B12",
  panel: "#0F0F1A",
  border: "rgba(255,255,255,0.09)",
  textSoft: "rgba(255,255,255,0.70)",
  gold: "#D4AF37",
};

/* ---------------- Utils ---------------- */
const TEXT_EXT = new Set(["txt", "py", "js", "json", "md", "csv", "yml", "yaml", "xml", "java", "html", "css"]);
const CODE_EXT = new Set(["py", "js", "ts", "java", "cpp", "c", "go", "rb", "php", "rs"]);
const IMG_EXT = new Set(["png", "jpg", "jpeg", "gif", "svg"]);
const ext = (n) => (n.includes(".") ? n.split(".").pop().toLowerCase() : "");

/* ---------------- Monaco modal ---------------- */
function ModalEditor({ open, fileName, language, value, onClose }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      <div className="absolute inset-0 flex items-center justify-center p-4">
        <div
          className="w-full max-w-5xl rounded-2xl overflow-hidden border shadow-2xl"
          style={{ background: COLORS.panel, borderColor: COLORS.border }}
        >
          <div className="px-4 py-3 flex items-center gap-3 border-b" style={{ borderColor: COLORS.border }}>
            <div className="text-sm font-semibold">{fileName}</div>
            <button
              onClick={onClose}
              className="ml-auto px-3 py-2 rounded-xl border text-xs"
              style={{ borderColor: COLORS.border }}
            >
              Close
            </button>
          </div>
          <div className="h-[70vh]">
            <Editor
              height="100%"
              language={language || "plaintext"}
              theme="vs-dark"
              value={value}
              options={{ minimap: { enabled: false }, fontSize: 14, readOnly: true }}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

/* ---------------- Main ---------------- */
export default function RetrainView() {
  const username = localStorage.getItem("username") || "pg";
  const BASE_RETRAIN = "http://127.0.0.1:8000";

  const [projects, setProjects] = useState([]);
  const [project, setProject] = useState("");
  const [path, setPath] = useState("");
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);

  const [selectedPaths, setSelectedPaths] = useState([]);
  const [requirementsPath, setRequirementsPath] = useState("");
  const [saveVersion, setSaveVersion] = useState("v2");

  const [consoleText, setConsoleText] = useState("");
  const [running, setRunning] = useState(false);
  const [jobId, setJobId] = useState("");
  const [manualJobId, setManualJobId] = useState("");

  const [modalOpen, setModalOpen] = useState(false);
  const [modalName, setModalName] = useState("");
  const [modalText, setModalText] = useState("");
  const [modalLang, setModalLang] = useState("");

  /* -------- load projects + files -------- */
  const loadProjects = async () => {
    const list = await StorageAPI.listProjects(username);
    setProjects(list || []);
    if (!project && list.length) setProject(list[0]);
  };

  const loadFiles = async (pjt = project, folder = path) => {
    if (!pjt) return;
    setLoading(true);
    const list = await StorageAPI.listFiles(username, pjt, folder || "");
    const normalized = list.map((f) => {
      if (!f.includes(".")) return f.endsWith("/") ? f : f + "/";
      return f;
    });
    setItems(normalized || []);
    setLoading(false);
  };

  useEffect(() => {
    loadProjects();
  }, []);
  useEffect(() => {
    if (project) loadFiles(project, path);
  }, [project, path]);

  /* -------- nav -------- */
  const enter = (dir) => setPath(path ? `${path}/${dir.replace(/\/$/, "")}` : dir.replace(/\/$/, ""));
  const up = () => {
    const parts = path.split("/").filter(Boolean);
    parts.pop();
    setPath(parts.join("/"));
  };

  /* -------- selection -------- */
  const toggleSelect = (name) => {
    const full = `${username}/${project}/${path ? path + "/" : ""}${name}`;
    setSelectedPaths((prev) => (prev.includes(full) ? prev.filter((x) => x !== full) : [...prev, full]));
  };

  const selectReq = (name) => {
    const full = `${username}/${project}/${path ? path + "/" : ""}${name}`;
    setRequirementsPath(full);
  };

  const copyPath = (name) => {
    const full = `${username}/${project}/${path ? path + "/" : ""}${name}`;
    navigator.clipboard.writeText(full);
  };

  /* -------- viewer -------- */
  const viewFile = async (name) => {
    try {
      const url = await StorageAPI.getFileUrl(username, project, name, path);
      const res = await fetch(url);
      const text = await res.text();
      const e = ext(name);
      const lang =
        e === "py"
          ? "python"
          : e === "js"
          ? "javascript"
          : e === "json"
          ? "json"
          : e === "html"
          ? "html"
          : e === "css"
          ? "css"
          : "plaintext";
      setModalName(name);
      setModalText(text);
      setModalLang(lang);
      setModalOpen(true);
    } catch {}
  };

  /* -------- retrain trigger -------- */
  const startRetrain = async () => {
    if (!selectedPaths.length) return alert("Select files or folders first.");
    if (!requirementsPath) return alert("Enter or select a requirements file path.");
    if (!saveVersion.trim()) return alert("Enter version.");

    const payload = {
      username,
      projectName: project,
      files: selectedPaths,
      saveBase: `${username}/${project}/artifacts/versions/${saveVersion}`,
      requirementsPath,
    };
    const res = await fetch(`${BASE_RETRAIN}/retrain`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!res.ok) return alert(await res.text());
    const data = await res.json();

    const generatedId = data.jobId || data.id || "";
    setJobId(generatedId);
    setManualJobId(generatedId);
    setRunning(true);
    setConsoleText("Retrain started...\n");

    // --- Popup for jobId ---
    window.alert(`Retrain job started!\n\nJob ID:\n${generatedId}\n\nCopy and paste it below to refresh console logs later.`);
    navigator.clipboard.writeText(generatedId);
  };

  /* -------- manual refresh console -------- */
  const refreshConsole = async () => {
    const targetId = manualJobId || jobId;
    if (!targetId) return alert("Enter a job ID first.");
    try {
      const res = await fetch(`${BASE_RETRAIN}/console/${targetId}`);
      if (!res.ok) throw new Error("Failed to fetch console output.");
      const data = await res.json();
      const text = `[Job ID: ${data.jobId}] | Status: ${data.status}\n\nSTDOUT:\n${data.stdout}\n\nSTDERR:\n${data.stderr}`;
      setConsoleText(text);
    } catch (err) {
      setConsoleText("❌ Error fetching console: " + err.message);
    }
  };

  /* -------- derived -------- */
  const list = useMemo(() => {
    const dirs = items.filter((f) => f.endsWith("/"));
    const files = items.filter((f) => !f.endsWith("/"));
    return [...dirs, ...files];
  }, [items]);

  /* -------- UI -------- */
  return (
    <div className="min-h-screen" style={{ background: COLORS.bg, color: "white" }}>
      {/* header */}
      <div
        className="sticky top-0 z-20 border-b px-6 py-3 flex items-center justify-between"
        style={{ borderColor: COLORS.border, background: "rgba(11,11,18,0.7)" }}
      >
        <div className="flex items-center gap-3">
          <div className="text-xs text-white/60">Retrain Module</div>
          <select
            className="bg-transparent border px-2 py-1 rounded text-sm"
            style={{ borderColor: COLORS.border }}
            value={project}
            onChange={(e) => {
              setProject(e.target.value);
              setPath("");
            }}
          >
            {projects.map((p) => (
              <option key={p} value={p} className="bg-black">
                {p}
              </option>
            ))}
          </select>
          <input
            type="text"
            placeholder="version"
            value={saveVersion}
            onChange={(e) => setSaveVersion(e.target.value)}
            className="bg-transparent border px-3 py-1 rounded text-sm w-28"
            style={{ borderColor: COLORS.border, color: "white" }}
          />
          <input
            type="text"
            placeholder="requirements.txt path"
            value={requirementsPath}
            onChange={(e) => setRequirementsPath(e.target.value)}
            className="bg-transparent border px-3 py-1 rounded text-sm w-[350px]"
            style={{ borderColor: COLORS.border, color: COLORS.gold }}
          />
        </div>
        <div className="flex items-center gap-3">
          <div className="text-xs text-white/70">
            Selected: <b>{selectedPaths.length}</b>
          </div>
          <button
            onClick={startRetrain}
            disabled={running}
            className="px-4 py-2 rounded-xl border"
            style={{
              borderColor: COLORS.border,
              background: running ? "#444" : COLORS.gold,
              color: running ? "#aaa" : "#000",
              fontWeight: 600,
            }}
          >
            {running ? "Running…" : "Start Retrain"}
          </button>
        </div>
      </div>

      {/* file browser */}
      <div className="max-w-7xl mx-auto px-5 py-6">
        <div className="rounded-2xl overflow-hidden border" style={{ borderColor: COLORS.border, background: COLORS.panel }}>
          <div
            className="grid grid-cols-[1fr_120px_120px_160px_100px] px-4 py-2 text-xs uppercase tracking-wider border-b"
            style={{ borderColor: COLORS.border, color: COLORS.textSoft }}
          >
            <span>Name</span>
            <span>Type</span>
            <span className="text-center">Select</span>
            <span className="text-center">Set requirements</span>
            <span className="text-center">Copy path</span>
          </div>

          {path && (
            <div
              className="px-4 py-2 border-b cursor-pointer hover:bg-white/5"
              style={{ borderColor: COLORS.border }}
              onClick={up}
            >
              ↩️ ..
            </div>
          )}

          {loading ? (
            <div className="p-6 text-white/60">Loading…</div>
          ) : (
            list.map((name) => {
              const isDir = name.endsWith("/");
              const label = name.replace(/\/$/, "");
              const e = ext(name);
              const tag = isDir
                ? "folder"
                : CODE_EXT.has(e)
                ? "code"
                : IMG_EXT.has(e)
                ? "image"
                : TEXT_EXT.has(e)
                ? "text"
                : "file";

              const fullPath = `${username}/${project}/${path ? path + "/" : ""}${label}`;

              return (
                <div
                  key={name}
                  className="grid grid-cols-[1fr_120px_120px_160px_100px] px-4 py-2 border-b hover:bg-white/5"
                  style={{ borderColor: COLORS.border }}
                >
                  <div className="flex items-center gap-2">
                    {isDir ? "📁" : "📄"}
                    {isDir ? (
                      <span
                        onClick={() => enter(label)}
                        className="text-blue-400 cursor-pointer hover:underline select-none"
                      >
                        {label}
                      </span>
                    ) : (
                      <span
                        onClick={() => viewFile(label)}
                        className="cursor-pointer hover:underline select-none"
                      >
                        {label}
                      </span>
                    )}
                  </div>
                  <div className="text-white/70">{tag}</div>
                  <div className="text-center">
                    <input type="checkbox" checked={selectedPaths.includes(fullPath)} onChange={() => toggleSelect(label)} />
                  </div>
                  <div className="text-center">
                    {!isDir && label.toLowerCase().includes("requirement") && (
                      <button onClick={() => selectReq(label)} className="text-xs text-yellow-400 underline">
                        Use this
                      </button>
                    )}
                  </div>
                  <div className="text-center">
                    <button onClick={() => copyPath(label)} className="text-xs text-blue-400 underline">
                      Copy
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>

        {/* console + refresh */}
        <div
          className="mt-6 border rounded-2xl p-4"
          style={{ borderColor: COLORS.border, background: COLORS.panel }}
        >
          <div className="flex items-center justify-between mb-2">
            <div className="text-sm text-white/70">Console</div>
            <div className="flex items-center gap-2">
              <input
                placeholder="Enter Job ID or paste here"
                value={manualJobId}
                onChange={(e) => setManualJobId(e.target.value)}
                className="bg-transparent border px-2 py-1 rounded text-xs w-[340px]"
                style={{ borderColor: COLORS.border }}
              />
              <button
                onClick={refreshConsole}
                className="border px-3 py-1 rounded-lg text-xs hover:bg-white/10"
                style={{ borderColor: COLORS.border }}
              >
                Refresh Console
              </button>
            </div>
          </div>
          <pre className="bg-black/60 rounded p-3 text-xs h-80 overflow-auto whitespace-pre-wrap">
            {consoleText || "Console output will appear here..."}
          </pre>
        </div>
      </div>

      <ModalEditor open={modalOpen} fileName={modalName} language={modalLang} value={modalText} onClose={() => setModalOpen(false)} />
    </div>
  );
}