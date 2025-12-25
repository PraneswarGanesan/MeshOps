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
  <div className="min-h-screen text-white relative overflow-hidden" style={{ background: 'linear-gradient(to bottom, #0a0a0f 0%, #0f0f18 50%, #0a0a0f 100%)' }}>
    {/* Ambient glows */}
    <div className="pointer-events-none fixed inset-0 opacity-30">
      <div className="absolute inset-0 blur-[120px]" style={{ background: 'radial-gradient(800px 400px at 70% 10%, rgba(217, 158, 40, 0.15), transparent)' }} />
      <div className="absolute inset-0 blur-[100px]" style={{ background: 'radial-gradient(600px 350px at 30% 70%, rgba(59, 130, 246, 0.12), transparent)' }} />
    </div>

    {/* Animated mesh */}
    <div className="pointer-events-none fixed inset-0 opacity-20" style={{ backgroundImage: 'radial-gradient(circle at 20% 50%, rgba(217, 158, 40, 0.1) 0%, transparent 50%)', animation: 'float 20s ease-in-out infinite' }} />

    {/* Premium header */}
    <div className="sticky top-0 z-20 backdrop-blur-xl border-b"
         style={{ 
           background: 'rgba(10, 10, 15, 0.85)',
           borderColor: 'rgba(217, 158, 40, 0.15)',
           boxShadow: '0 4px 24px rgba(0, 0, 0, 0.4)'
         }}>
      <div className="px-6 py-5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-amber-500 via-amber-600 to-blue-600 shadow-lg shadow-amber-500/30 flex items-center justify-center">
              <span className="text-white font-bold text-lg">R</span>
            </div>
            <div>
              <div className="text-xs font-medium uppercase tracking-wider" style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
                AI Model Management
              </div>
              <div className="text-xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 via-amber-200 to-blue-300">
                Retrain Module
              </div>
            </div>
          </div>
          
          <div className="flex items-center gap-3">
            <div className="px-4 py-2 rounded-lg border text-sm font-medium"
                 style={{ 
                   borderColor: 'rgba(217, 158, 40, 0.3)',
                   background: 'rgba(217, 158, 40, 0.1)',
                   color: '#d4af37'
                 }}>
              <span className="opacity-70">Selected:</span> <b className="text-white ml-1">{selectedPaths.length}</b> files
            </div>
            <button
              onClick={startRetrain}
              disabled={running}
              className="px-6 py-2.5 rounded-xl border font-semibold transition-all duration-300 hover:scale-105 active:scale-95 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100"
              style={{
                borderColor: running ? 'rgba(217, 158, 40, 0.2)' : 'rgba(217, 158, 40, 0.4)',
                background: running ? 'rgba(100, 100, 100, 0.3)' : 'linear-gradient(135deg, rgba(217, 158, 40, 0.9), rgba(217, 158, 40, 0.7))',
                color: running ? '#aaa' : '#000',
                boxShadow: running ? 'none' : '0 0 30px rgba(217, 158, 40, 0.4)'
              }}
            >
              {running ? "⏳ Running…" : "🚀 Start Retrain"}
            </button>
          </div>
        </div>

        {/* Config inputs */}
        <div className="mt-4 grid grid-cols-3 gap-4">
          <div>
            <label className="text-[10px] uppercase tracking-widest mb-2 font-semibold block" style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
              📦 Project
            </label>
            <select
              className="w-full bg-transparent border rounded-xl px-4 py-2.5 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.2)',
                background: 'rgba(0, 0, 0, 0.3)',
                color: 'white'
              }}
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
          </div>
          
          <div>
            <label className="text-[10px] uppercase tracking-widest mb-2 font-semibold block" style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
              🏷️ Version
            </label>
            <input
              type="text"
              placeholder="e.g., v1.0.2"
              value={saveVersion}
              onChange={(e) => setSaveVersion(e.target.value)}
              className="w-full bg-transparent border rounded-xl px-4 py-2.5 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.2)',
                background: 'rgba(0, 0, 0, 0.3)',
                color: 'white'
              }}
            />
          </div>

          <div>
            <label className="text-[10px] uppercase tracking-widest mb-2 font-semibold block" style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
              📋 Requirements Path
            </label>
            <input
              type="text"
              placeholder="path/to/requirements.txt"
              value={requirementsPath}
              onChange={(e) => setRequirementsPath(e.target.value)}
              className="w-full bg-transparent border rounded-xl px-4 py-2.5 outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
              style={{ 
                borderColor: 'rgba(217, 158, 40, 0.2)',
                background: 'rgba(0, 0, 0, 0.3)',
                color: '#d4af37'
              }}
            />
          </div>
        </div>
      </div>
    </div>

    {/* Main content */}
    <div className="relative z-10 max-w-7xl mx-auto px-6 py-8 grid grid-cols-1 lg:grid-cols-[1fr_380px] gap-6">
      {/* File Browser */}
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
              <span className="text-amber-400 text-sm">📂</span>
            </div>
            <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
              File Selection Browser
            </div>
            {path && (
              <div className="ml-auto text-[10px] px-3 py-1.5 rounded-lg font-medium"
                   style={{ 
                     color: 'rgba(217, 158, 40, 0.8)',
                     background: 'rgba(217, 158, 40, 0.05)',
                     border: '1px solid rgba(217, 158, 40, 0.2)'
                   }}>
                📁 /{path}
              </div>
            )}
          </div>

          {/* Table Header */}
          <div className="grid grid-cols-[1fr_100px_80px_140px_90px] px-4 py-3 mb-2 rounded-lg text-[10px] uppercase tracking-widest font-semibold"
               style={{ 
                 background: 'rgba(217, 158, 40, 0.05)',
                 color: 'rgba(217, 158, 40, 0.8)',
                 borderBottom: '2px solid rgba(217, 158, 40, 0.15)'
               }}>
            <span>Name</span>
            <span>Type</span>
            <span className="text-center">Select</span>
            <span className="text-center">Requirements</span>
            <span className="text-center">Actions</span>
          </div>

          {/* File List */}
          <div className="space-y-1 max-h-[600px] overflow-auto custom-scrollbar">
            {path && (
              <div
                className="grid grid-cols-[1fr_100px_80px_140px_90px] px-4 py-3 rounded-lg cursor-pointer transition-all duration-200"
                style={{ background: 'rgba(217, 158, 40, 0.05)' }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = 'rgba(217, 158, 40, 0.12)';
                  e.currentTarget.style.transform = 'translateX(4px)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)';
                  e.currentTarget.style.transform = 'translateX(0)';
                }}
                onClick={up}
              >
                <div className="flex items-center gap-2 text-amber-300">
                  <span>↩️</span> <span className="font-medium">.. (Go back)</span>
                </div>
              </div>
            )}

            {loading ? (
              <div className="p-12 text-center">
                <div className="text-2xl mb-3 opacity-30">⏳</div>
                <div className="text-white/60">Loading files...</div>
              </div>
            ) : (
              list.map((name) => {
                const isDir = name.endsWith("/");
                const label = name.replace(/\/$/, "");
                const e = ext(name);
                const tag = isDir
                  ? "folder"
                  : CODE_EXT.has(e) ? "code"
                  : IMG_EXT.has(e) ? "image"
                  : TEXT_EXT.has(e) ? "text"
                  : "file";

                const fullPath = `${username}/${project}/${path ? path + "/" : ""}${label}`;
                const isSelected = selectedPaths.includes(fullPath);

                return (
                  <div
                    key={name}
                    className="grid grid-cols-[1fr_100px_80px_140px_90px] px-4 py-3 rounded-lg transition-all duration-200"
                    style={{ 
                      background: isSelected ? 'rgba(217, 158, 40, 0.15)' : 'rgba(217, 158, 40, 0.05)',
                      border: isSelected ? '1px solid rgba(217, 158, 40, 0.3)' : '1px solid transparent'
                    }}
                    onMouseEnter={(e) => {
                      if (!isSelected) e.currentTarget.style.background = 'rgba(217, 158, 40, 0.1)';
                    }}
                    onMouseLeave={(e) => {
                      if (!isSelected) e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)';
                    }}
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-amber-400/70">{isDir ? "📁" : "📄"}</span>
                      {isDir ? (
                        <span
                          onClick={() => enter(label)}
                          className="text-amber-300 cursor-pointer hover:text-amber-200 transition-colors font-medium"
                        >
                          {label}
                        </span>
                      ) : (
                        <span
                          onClick={() => viewFile(label)}
                          className="text-white/80 cursor-pointer hover:text-amber-300 transition-colors"
                        >
                          {label}
                        </span>
                      )}
                    </div>
                    
                    <div className="flex items-center">
                      <span className="px-2 py-1 rounded text-[10px] font-medium"
                            style={{ 
                              background: 'rgba(217, 158, 40, 0.1)',
                              color: 'rgba(217, 158, 40, 0.9)'
                            }}>
                        {tag}
                      </span>
                    </div>
                    
                    <div className="flex items-center justify-center">
                      <input 
                        type="checkbox" 
                        checked={isSelected} 
                        onChange={() => toggleSelect(label)}
                        className="w-4 h-4 accent-amber-500 cursor-pointer"
                      />
                    </div>
                    
                    <div className="flex items-center justify-center">
                      {!isDir && label.toLowerCase().includes("requirement") && (
                        <button 
                          onClick={() => selectReq(label)} 
                          className="text-xs px-3 py-1 rounded-lg border font-medium transition-all duration-200 hover:scale-105"
                          style={{ 
                            borderColor: 'rgba(217, 158, 40, 0.3)',
                            background: 'rgba(217, 158, 40, 0.1)',
                            color: '#d4af37'
                          }}>
                          ✓ Use this
                        </button>
                      )}
                    </div>
                    
                    <div className="flex items-center justify-center">
                      <button 
                        onClick={() => copyPath(label)} 
                        className="text-xs px-3 py-1 rounded-lg border font-medium transition-all duration-200 hover:scale-105"
                        style={{ 
                          borderColor: 'rgba(59, 130, 246, 0.3)',
                          background: 'rgba(59, 130, 246, 0.1)',
                          color: 'rgb(96, 165, 250)'
                        }}>
                        📋 Copy
                      </button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>

      {/* Console Panel */}
      <div className="space-y-6">
        {/* Job ID Input */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
             style={{ 
               borderColor: 'rgba(217, 158, 40, 0.15)', 
               background: 'rgba(15, 15, 24, 0.6)',
               boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
             }}>
          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                <span className="text-amber-400 text-sm">🎯</span>
              </div>
              <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                Job Monitoring
              </div>
            </div>
            
            <div className="space-y-3">
              <input
                placeholder="Enter Job ID to monitor..."
                value={manualJobId}
                onChange={(e) => setManualJobId(e.target.value)}
                className="w-full bg-transparent border rounded-xl px-4 py-2.5 text-sm outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
                style={{ 
                  borderColor: 'rgba(217, 158, 40, 0.2)',
                  background: 'rgba(0, 0, 0, 0.3)',
                  color: 'white'
                }}
              />
              <button
                onClick={refreshConsole}
                className="w-full px-4 py-2.5 rounded-xl border font-semibold transition-all duration-300 hover:scale-105 active:scale-95"
                style={{ 
                  borderColor: 'rgba(217, 158, 40, 0.3)',
                  background: 'rgba(217, 158, 40, 0.1)',
                  color: '#d4af37'
                }}>
                🔄 Refresh Console
              </button>
            </div>
          </div>
        </div>

        {/* Console Output */}
        <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
             style={{ 
               borderColor: 'rgba(217, 158, 40, 0.15)', 
               background: 'rgba(15, 15, 24, 0.6)',
               boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
             }}>
          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500/20 to-blue-500/20 border border-amber-400/30 flex items-center justify-center">
                <span className="text-amber-400 text-sm">🖥️</span>
              </div>
              <div className="text-sm font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300">
                Console Output
              </div>
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
              <pre className="text-xs h-[500px] overflow-auto font-mono custom-scrollbar pt-6 whitespace-pre-wrap"
                   style={{ color: '#a1a1aa' }}>
{consoleText || "💤 Console output will appear here...\n\n🚀 Start a retrain job or enter a Job ID to monitor progress."}
              </pre>
            </div>
          </div>
        </div>
      </div>
    </div>

    <ModalEditor open={modalOpen} fileName={modalName} language={modalLang} value={modalText} onClose={() => setModalOpen(false)} />

    <style jsx>{`
      @keyframes shimmer {
        0% { background-position: -200% 0; }
        100% { background-position: 200% 0; }
      }
      @keyframes float {
        0%, 100% { transform: translateY(0px); }
        50% { transform: translateY(-20px); }
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
);
}