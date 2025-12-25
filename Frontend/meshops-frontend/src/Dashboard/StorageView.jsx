// src/Dashboard/StorageView.jsx
import React, { useEffect, useMemo, useRef, useState } from "react";
import Editor from "@monaco-editor/react";
import { StorageAPI } from "../api/storage";

/* ---------------- Theme ---------------- */
const COLORS = {
  bg: "#0B0B12",
  panel: "#0F0F1A",
  panelAlt: "#13131F",
  border: "rgba(255,255,255,0.09)",
  textSoft: "rgba(255,255,255,0.70)",
  gold: "#D4AF37",
  goldGlow: "rgba(212,175,55,0.32)",
};

/* ---------------- Utils ---------------- */
const TEXT_EXT = new Set([
  "txt","md","csv","json","yaml","yml","py","js","ts","jsx","tsx",
  "html","css","java","xml","sh","properties","ini","conf","cfg"
]);
const CODE_EXT = new Set(["py","js","ts","jsx","tsx","java","c","cpp","cs","go","rb","php","rs","sh"]);
const IMG_EXT  = new Set(["png","jpg","jpeg","gif","webp","svg"]);
const DATA_EXT = new Set(["csv","json","yaml","yml","xml","parquet"]);
const DOC_EXT  = new Set(["txt","md","pdf","doc","docx","xls","xlsx"]);
const ext = (n) => (n.includes(".") ? n.split(".").pop().toLowerCase() : "");

/* ---------------- Tiny Icons (inline SVG) ---------------- */
const Icon = {
  Folder: (p) => (<svg width="18" height="18" viewBox="0 0 24 24" fill="none" {...p}><path d="M3 6h6l2 2h10v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6z" stroke="currentColor" strokeWidth="1.4" /></svg>),
  File:   (p) => (<svg width="18" height="18" viewBox="0 0 24 24" fill="none" {...p}><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" strokeWidth="1.4"/><path d="M14 2v6h6" stroke="currentColor" strokeWidth="1.4"/></svg>),
  Edit:   (p) => (<svg width="16" height="16" viewBox="0 0 24 24" fill="none" {...p}><path d="M12 20h9" stroke="currentColor" strokeWidth="1.5"/><path d="M16.5 3.5l4 4L8 20 4 20 4 16z" stroke="currentColor" strokeWidth="1.5"/></svg>),
  Download:(p)=> (<svg width="16" height="16" viewBox="0 0 24 24" fill="none" {...p}><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" stroke="currentColor" strokeWidth="1.5"/><path d="M7 10l5 5 5-5" stroke="currentColor" strokeWidth="1.5"/><path d="M12 15V3" stroke="currentColor" strokeWidth="1.5"/></svg>),
  Trash:  (p) => (<svg width="16" height="16" viewBox="0 0 24 24" fill="none" {...p}><path d="M3 6h18" stroke="currentColor" strokeWidth="1.5"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" stroke="currentColor" strokeWidth="1.5"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" stroke="currentColor" strokeWidth="1.5"/></svg>),
  ChevronR:(p) => (<svg width="14" height="14" viewBox="0 0 24 24" fill="none" {...p}><path d="M9 18l6-6-6-6" stroke="currentColor" strokeWidth="1.5"/></svg>),
  Search: (p) => (<svg width="16" height="16" viewBox="0 0 24 24" fill="none" {...p}><circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="1.5"/><path d="M20 20l-3-3" stroke="currentColor" strokeWidth="1.5"/></svg>),
};

/* ---------------- Small UI atoms ---------------- */
const IconBtn = ({ title, className = "", ...p }) => (
  <button
    {...p}
    title={title}
    className={`inline-flex items-center justify-center w-8 h-8 rounded-lg border transition hover:opacity-100 focus:outline-none focus:ring-2 ${className}`}
    style={{ borderColor: COLORS.border, color: "#EDEDED" }}
  />
);

const Badge = ({ children }) => (
  <span className="px-2 py-1 rounded-full text-[10px] uppercase tracking-wide border"
        style={{ borderColor: COLORS.border, color: COLORS.textSoft }}>
    {children}
  </span>
);

/* ---------------- Donut (real % code files) ---------------- */
function Donut({ percent = 0, label = "" }) {
  const r = 42, c = 2 * Math.PI * r;
  const pct = Math.max(0, Math.min(100, percent));
  return (
    <div className="flex items-center gap-4">
      <svg viewBox="0 0 100 100" className="w-28 h-28">
        <circle cx="50" cy="50" r={r} stroke="#2a2a33" strokeWidth="12" fill="none" />
        <circle cx="50" cy="50" r={r} stroke={COLORS.gold} strokeWidth="12" fill="none"
                strokeDasharray={`${(pct/100)*c} ${c}`}
                strokeLinecap="round" transform="rotate(-90 50 50)" />
        <text x="50" y="53" textAnchor="middle" fontSize="14" fill="#fff">{pct}%</text>
      </svg>
      <div className="text-sm" style={{ color: COLORS.textSoft }}>
        <div className="text-white font-semibold">{label}</div>
        <div>Share of code files in this folder</div>
      </div>
    </div>
  );
}

/* ---------------- Usage Graph (dummy, SVG line) ---------------- */
function UsageGraph({ seed = { code:0, images:0, data:0, docs:0, other:0 } }) {
  const n = 60;
  const base =
    seed.code * 5 + seed.images * 3 + seed.data * 4 + seed.docs * 2 + seed.other;
  const pts = Array.from({ length: n }, (_, i) => {
    const wave = Math.sin((i + base) / 6) * 8;
    const noise = ((i * 31) % 13) - 6;
    return Math.max(2, 40 + wave + noise);
  });
  const maxY = 60;
  const path = pts
    .map((y, i) => `${i === 0 ? "M" : "L"} ${i * 3},${maxY - y}`)
    .join(" ");

  return (
    <svg viewBox={`0 0 ${n * 3} ${maxY}`} className="w-full h-24">
      <path d={path} fill="none" stroke="#e6e6e6" strokeWidth="1.5" />
    </svg>
  );
}

/* ---------------- Monaco Modal Editor ---------------- */
function ModalEditor({ open, fileName, language, value, onChange, onClose, onSave, saving }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40">
      {/* backdrop */}
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      {/* dialog */}
      <div className="absolute inset-0 flex items-center justify-center p-4">
        <div className="w-full max-w-5xl rounded-2xl overflow-hidden border shadow-2xl"
             style={{ background: COLORS.panel, borderColor: COLORS.border }}>
          <div className="px-4 py-3 flex items-center gap-3 border-b" style={{ borderColor: COLORS.border }}>
            <Badge>Editing</Badge>
            <div className="font-semibold truncate">{fileName}</div>
            <div className="ml-auto flex items-center gap-2">
              <button
                onClick={onSave}
                disabled={saving}
                className="px-3 py-2 rounded-xl border text-xs"
                style={{ borderColor: COLORS.border, opacity: saving ? 0.6 : 1 }}
              >
                {saving ? "Saving…" : "Save"}
              </button>
              <button
                onClick={onClose}
                className="px-3 py-2 rounded-xl border text-xs"
                style={{ borderColor: COLORS.border }}
              >
                Close
              </button>
            </div>
          </div>
          <div className="h-[70vh]">
            <Editor
              height="100%"
              language={language || "plaintext"}
              theme="vs-dark"
              value={value}
              onChange={(v) => onChange(v || "")}
              options={{ minimap: { enabled: false }, fontSize: 14 }}
            />
          </div>
          <div className="px-4 py-2 text-[11px]" style={{ color: COLORS.textSoft }}>
            Saving overwrites <span className="text-white">{fileName}</span> in-place.
          </div>
        </div>
      </div>
    </div>
  );
}

/* ---------------- Main ---------------- */
export default function StorageView() {
  const username = localStorage.getItem("username") || localStorage.getItem("email") || "pg";
  const BASE = "http://localhost:8081";

  // list state
  const [projects, setProjects] = useState([]);
  const [project, setProject]   = useState("");
  const [path, setPath]         = useState("");
  const [items, setItems]       = useState([]); // ["dir/","file.txt",...]
  const [loading, setLoading]   = useState(false);
  const [uploading, setUploading] = useState(false);

  // editor modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [modalName, setModalName] = useState("");
  const [modalLang, setModalLang] = useState("plaintext");
  const [modalText, setModalText] = useState("");
  const [saving, setSaving]       = useState(false);

  // ui helpers
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("ready");

  /* ----- API helpers ----- */
  const api = async (url, init) => {
    setStatus(url);
    const res = await fetch(url, init);
    if (!res.ok) {
      const t = await res.text().catch(()=>"");
      const err = new Error(`${res.status} ${res.statusText} @ ${url}\n${t}`);
      err._status = res.status; err._body = t; throw err;
    }
    const ct = res.headers.get("content-type") || "";
    if (ct.includes("application/json")) return res.json();
    return res;
  };

  // Create new project: S3 folder create (8081) then ensure in MySQL (8082)
  const createNewProject = async () => {
    const name = prompt("Enter new project name:");
    if (!name) return;
    try {
      setStatus("Creating project…");
      const s3Msg = await StorageAPI.createProject(username, name);
      console.log("[createProject]", s3Msg);

      // Build s3Prefix and ensure in projects service
      const payload = {
        username,
        projectName: name,
        s3Prefix: `s3://my-users-meshops-bucket/${username}/${name}/`,
      };
      console.log("[ensureProject] payload:", payload);
      await StorageAPI.ensureProject(username, name);

      // Refresh projects and select new one
      await loadProjects();
      setProject(name);
      setPath("");
      setStatus("Project created and ensured");
      alert("Project created successfully");
    } catch (e) {
      console.error(e);
      setStatus(e.message || "Failed to create project");
      alert("Failed: " + (e.message || String(e)));
    }
  };
  const fileURL = (name, useFolder = true) => {
    const u = new URL(
      `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/${encodeURIComponent(name)}`
    );
    if (useFolder && path) u.searchParams.set("folder", path);
    return u.toString();
  };

  const loadProjects = async () => {
    try {
      const url = `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects`;
      const data = await api(url);
      const arr = Array.isArray(data) ? data : [];
      setProjects(arr);
      const last = localStorage.getItem("lastProject");
      const chosen = last && arr.includes(last) ? last : (arr[0] || "");
      if (chosen) setProject(chosen);
      setStatus(`OK (${arr.length} projects)`);
    } catch (e) {
      console.error(e); setProjects([]); setStatus(e.message);
    }
  };
  const loadFiles = async (pjt = project, pth = path) => {
    if (!pjt) { setItems([]); return; }
    setLoading(true);
    try {
      const u = new URL(
        `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(pjt)}/files`
      );
      if (pth) u.searchParams.set("folder", pth);
      const data = await api(u.toString());
      setItems(Array.isArray(data) ? data : []);
      setStatus(`OK (${Array.isArray(data) ? data.length : 0} items)`);
    } catch (e) {
      console.error(e); setItems([]); setStatus(e.message);
    } finally { setLoading(false); }
  };

  useEffect(() => { loadProjects(); }, []);
  useEffect(() => {
    if (project) localStorage.setItem("lastProject", project);
    loadFiles();
    // close any open editor when navigating
    setModalOpen(false);
    setModalName(""); setModalText(""); setModalLang("plaintext");
  }, [project, path]);

  /* ----- derived: normalized & stats ----- */
  const norm = useMemo(() => items.map((s)=>({ name:s, isDir: s.endsWith("/") })), [items]);

  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    const arr = !q ? norm : norm.filter(x=>x.name.toLowerCase().includes(q));
    const dirs = arr.filter(x=>x.isDir).sort((a,b)=>a.name.localeCompare(b.name));
    const files= arr.filter(x=>!x.isDir).sort((a,b)=>a.name.localeCompare(b.name));
    return [...dirs, ...files];
  }, [norm, query]);

  const stats = useMemo(() => {
    const inFolder = norm.length;
    const folderCount = norm.filter(x=>x.isDir).length;
    const fileNames = norm.filter(x=>!x.isDir).map(x=>x.name);
    const fileCount = fileNames.length;

    let code=0, images=0, data=0, docs=0, other=0;
    fileNames.forEach(n=>{
      const e = ext(n);
      if (CODE_EXT.has(e)) code++;
      else if (IMG_EXT.has(e)) images++;
      else if (DATA_EXT.has(e)) data++;
      else if (DOC_EXT.has(e)) docs++;
      else other++;
    });

    const codePct = fileCount ? Math.round((code/fileCount)*100) : 0;

    return {
      projects: projects.length,
      inFolder, folderCount, fileCount,
      byType: { code, images, data, docs, other },
      codePct
    };
  }, [norm, projects.length]);

  /* ----- nav & file ops ----- */
  const enter = (dir) => setPath(path ? `${path}/${dir.replace(/\/$/, "")}` : dir.replace(/\/$/, ""));
  const up = () => { const p = path.split("/").filter(Boolean); p.pop(); setPath(p.join("/")); };

  const onUpload = async (e) => {
    const file = e.target.files?.[0]; if (!file || !project) return; setUploading(true);
    try {
      const u = new URL(`${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/upload`);
      if (path) u.searchParams.set("folder", path);
      const form = new FormData(); form.append("file", file);
      await api(u.toString(), { method: "POST", body: form }); await loadFiles();
    } catch (e2) { console.error(e2); setStatus(e2.message); }
    finally { setUploading(false); e.target.value = ""; }
  };

  const looksLikeNoSuchKey = (err) => {
    const body = (err && err._body) || ""; const code = err && err._status;
    return body.includes("NoSuchKey") || body.includes("does not exist") || [404,400,500].includes(code);
  };
  const doDownload = async (name) => {
    try {
      const res = await api(fileURL(name)); const blob = await res.blob();
      const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = name; a.click(); URL.revokeObjectURL(a.href);
    } catch (e) { if (looksLikeNoSuchKey(e)) { setStatus("Looks like a folder — opening…"); enter(name); } else setStatus(e.message); }
  };
  const doDelete = async (name, isDir = false) => {
  if (isDir) {
    alert("Deleting folders is not supported here.");
    return;
  }
  try {
    const u = new URL(
      `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/delete/${encodeURIComponent(name)}`
    );
    if (path) u.searchParams.set("folder", path);

    const res = await fetch(u.toString(), { method: "DELETE" });
    if (!res.ok) {
      const t = await res.text();
      throw new Error(`${res.status} ${res.statusText}\n${t}`);
    }

    setStatus("Deleted " + name);
    await loadFiles();
  } catch (e) {
    console.error("Delete failed:", e);
    setStatus("Delete failed: " + e.message);
  }
};


  // open Monaco modal editor
  const openEditor = async (name) => {
    setStatus("opening…");
    try {
      const res = await api(fileURL(name)); const blob = await res.blob();
      if (!TEXT_EXT.has(ext(name))) { setStatus("no preview for this type"); return; }
      const txt = await blob.text();
      const e = ext(name);
      const lang =
        e === "py" ? "python" :
        e === "js" ? "javascript" :
        e === "ts" ? "typescript" :
        e === "jsx" ? "javascript" :
        e === "tsx" ? "typescript" :
        e === "json" ? "json" :
        e === "yaml" || e === "yml" ? "yaml" :
        e === "html" ? "html" :
        e === "css" ? "css" :
        "plaintext";
      setModalName(name); setModalText(txt); setModalLang(lang); setModalOpen(true);
      setStatus("editor ready");
    } catch (e) {
      if (looksLikeNoSuchKey(e)) { setStatus("Looks like a folder — opening…"); enter(name); }
      else setStatus(e.message);
    }
  };

  const saveEditor = async () => {
    if (!modalOpen || !modalName) return; setSaving(true);
    try {
      const u = new URL(`${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/upload`);
      if (path) u.searchParams.set("folder", path);
      const blob = new Blob([modalText], { type: "text/plain" });
      const file = new File([blob], modalName, { type: "text/plain" });
      const form = new FormData(); form.append("file", file);
      await api(u.toString(), { method: "POST", body: form });
      setStatus("saved");
      setModalOpen(false);
      await loadFiles();
    } catch (e) { setStatus(e.message); }
    finally { setSaving(false); }
  };

  /* ----- UI ----- */
return (
  <div className="min-h-screen relative overflow-hidden" style={{ background: 'linear-gradient(to bottom, #0a0a0f 0%, #0f0f18 50%, #0a0a0f 100%)' }}>
    {/* Enhanced ambient glow with golden-blue theme */}
    <div className="pointer-events-none fixed inset-0 opacity-30">
      <div className="absolute inset-0 blur-[120px]" 
           style={{ background: 'radial-gradient(800px 400px at 80% 10%, rgba(217, 158, 40, 0.15), transparent)' }} />
      <div className="absolute inset-0 blur-[100px]" 
           style={{ background: 'radial-gradient(600px 350px at 20% 60%, rgba(59, 130, 246, 0.12), transparent)' }} />
      <div className="absolute inset-0 blur-[80px]" 
           style={{ background: 'radial-gradient(500px 300px at 90% 80%, rgba(168, 85, 247, 0.08), transparent)' }} />
    </div>

    {/* Animated gradient mesh overlay */}
    <div className="pointer-events-none fixed inset-0 opacity-20"
         style={{ 
           backgroundImage: 'radial-gradient(circle at 20% 50%, rgba(217, 158, 40, 0.1) 0%, transparent 50%), radial-gradient(circle at 80% 80%, rgba(59, 130, 246, 0.1) 0%, transparent 50%)',
           backgroundSize: '100% 100%',
           animation: 'float 20s ease-in-out infinite'
         }} />

    {/* Premium top bar with glassmorphism */}
    <div className="sticky top-0 z-20 border-b backdrop-blur-xl"
         style={{ 
           borderColor: 'rgba(217, 158, 40, 0.1)', 
           background: 'rgba(10, 10, 15, 0.8)',
           boxShadow: '0 4px 24px rgba(0, 0, 0, 0.3)'
         }}>
      <div className="max-w-7xl mx-auto px-6 py-4 flex items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500 via-amber-600 to-blue-600 shadow-lg shadow-amber-500/30 flex items-center justify-center">
            <span className="text-white font-bold text-sm">S</span>
          </div>
          <div>
            <div className="text-transparent bg-clip-text bg-gradient-to-r from-amber-300 via-amber-200 to-blue-300 font-bold tracking-wide text-sm">
              Storage Studio
            </div>
          </div>
        </div>
        <div className="ml-2 px-3 py-1.5 rounded-lg border text-xs font-medium"
             style={{ 
               borderColor: 'rgba(217, 158, 40, 0.2)', 
               background: 'rgba(217, 158, 40, 0.05)',
               color: '#d4af37'
             }}>
          <span className="opacity-70">User:</span> <b className="ml-1 text-white">{username}</b>
        </div>
        <div className="ml-auto text-xs font-medium px-3 py-1.5 rounded-lg" 
             style={{ 
               color: 'rgba(255, 255, 255, 0.5)',
               background: 'rgba(255, 255, 255, 0.03)'
             }}>
          {status}
        </div>
      </div>
    </div>

    <div className="relative z-10 max-w-7xl mx-auto grid gap-6 px-6 py-8">
      {/* PREMIUM SUMMARY PANEL */}
      <div className="rounded-2xl p-6 border backdrop-blur-sm relative overflow-hidden group"
           style={{ 
             borderColor: 'rgba(217, 158, 40, 0.15)', 
             background: 'rgba(15, 15, 24, 0.6)',
             boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
           }}>
        {/* Subtle shimmer effect */}
        <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700"
             style={{
               background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
               backgroundSize: '200% 100%',
               animation: 'shimmer 3s infinite'
             }} />
        
        <div className="relative z-10 grid gap-6 lg:grid-cols-[1.2fr_1fr]">
          {/* Left: Enhanced controls */}
          <div>
            <div className="grid md:grid-cols-3 gap-4">
              {/* Project Selector */}
              <div>
                <div className="text-[10px] uppercase tracking-widest mb-2 font-semibold" 
                     style={{ color: 'rgba(217, 158, 40, 0.7)' }}>Project</div>
                <div className="relative flex items-center gap-2">
                  <select
                    className="w-full appearance-none rounded-xl px-4 py-2.5 pr-10 bg-transparent border outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
                    style={{ 
                      borderColor: 'rgba(217, 158, 40, 0.2)', 
                      color: 'white',
                      background: 'rgba(0, 0, 0, 0.3)'
                    }}
                    value={project}
                    onChange={(e)=>{ setProject(e.target.value); setPath(""); }}
                  >
                    {projects.length===0 && <option value="">No projects</option>}
                    {projects.map((p)=>(<option key={p} value={p} className="bg-black">{p}</option>))}
                  </select>
                  <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 opacity-70 text-amber-300">
                    <Icon.ChevronR/>
                  </span>
                  <button onClick={createNewProject} 
                          className="px-4 py-2.5 rounded-xl border text-xs font-semibold transition-all duration-300 hover:scale-105 hover:shadow-lg active:scale-95"
                          style={{ 
                            borderColor: 'rgba(217, 158, 40, 0.3)',
                            background: 'linear-gradient(135deg, rgba(217, 158, 40, 0.1), rgba(59, 130, 246, 0.05))',
                            color: '#d4af37',
                            boxShadow: '0 0 20px rgba(217, 158, 40, 0.1)'
                          }}>
                    New
                  </button>
                </div>
              </div>

              {/* Folder Path */}
              <div>
                <div className="text-[10px] uppercase tracking-widest mb-2 font-semibold" 
                     style={{ color: 'rgba(217, 158, 40, 0.7)' }}>Folder</div>
                <div className="flex items-center gap-2">
                  <div className="flex-1 rounded-xl px-4 py-2.5 border backdrop-blur-sm"
                       style={{ 
                         borderColor: 'rgba(217, 158, 40, 0.2)', 
                         color: 'rgba(255, 255, 255, 0.6)',
                         background: 'rgba(0, 0, 0, 0.3)'
                       }}>
                    /{project}{path?`/${path}`:""}
                  </div>
                  {path && (
                    <button onClick={up} 
                            className="px-4 py-2.5 rounded-xl border font-medium transition-all duration-300 hover:scale-105 active:scale-95"
                            style={{ 
                              borderColor: 'rgba(217, 158, 40, 0.2)',
                              background: 'rgba(217, 158, 40, 0.05)',
                              color: '#d4af37'
                            }}>
                      Up
                    </button>
                  )}
                </div>
              </div>

              {/* Search */}
              <div>
                <div className="text-[10px] uppercase tracking-widest mb-2 font-semibold" 
                     style={{ color: 'rgba(217, 158, 40, 0.7)' }}>Search</div>
                <div className="relative">
                  <input className="w-full rounded-xl px-10 py-2.5 bg-transparent border outline-none transition-all duration-300 focus:border-amber-400/40 focus:shadow-[0_0_0_3px_rgba(217,158,40,0.15)]"
                         style={{ 
                           borderColor: 'rgba(217, 158, 40, 0.2)', 
                           color: 'white',
                           background: 'rgba(0, 0, 0, 0.3)'
                         }} 
                         placeholder="Type to filter…"
                         value={query} 
                         onChange={(e)=>setQuery(e.target.value)} />
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-amber-400/70"><Icon.Search/></span>
                </div>
              </div>
            </div>

            {/* Premium stats chips */}
            <div className="mt-4 flex flex-wrap items-center gap-2 text-xs font-medium">
              {[
                { label: 'Projects', value: stats.projects },
                { label: 'In folder', value: stats.inFolder },
                { label: 'Folders', value: stats.folderCount },
                { label: 'Files', value: stats.fileCount },
                { label: 'Code', value: stats.byType.code },
                { label: 'Images', value: stats.byType.images },
                { label: 'Data', value: stats.byType.data },
                { label: 'Docs', value: stats.byType.docs }
              ].map((stat, i) => (
                <span key={i} className="px-3 py-1.5 rounded-lg border backdrop-blur-sm transition-all duration-300 hover:scale-105" 
                      style={{ 
                        borderColor: 'rgba(217, 158, 40, 0.15)',
                        background: 'rgba(217, 158, 40, 0.05)',
                        color: 'rgba(255, 255, 255, 0.6)'
                      }}>
                  {stat.label}: <b className="text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300 ml-1">{stat.value}</b>
                </span>
              ))}
            </div>
          </div>

          {/* Right: Enhanced donut + graph */}
          <div className="flex flex-col items-end gap-4">
            <Donut percent={stats.codePct} label={`${stats.byType.code}/${stats.fileCount} files are code`} />
            <div className="w-full max-w-sm rounded-xl border p-4 backdrop-blur-sm"
                 style={{ 
                   borderColor: 'rgba(217, 158, 40, 0.15)', 
                   background: 'rgba(0, 0, 0, 0.3)'
                 }}>
              <div className="text-[10px] uppercase tracking-widest mb-2 font-semibold" 
                   style={{ color: 'rgba(217, 158, 40, 0.7)' }}>
                Folder Activity 
              </div>
              <UsageGraph seed={stats.byType} />
            </div>
          </div>
        </div>
      </div>

      {/* PREMIUM BROWSER TABLE */}
      <div className="rounded-2xl overflow-hidden border backdrop-blur-sm relative group"
           style={{ 
             borderColor: 'rgba(217, 158, 40, 0.15)', 
             background: 'rgba(15, 15, 24, 0.6)',
             boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)'
           }}>
        {/* Shimmer effect */}
        <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700 pointer-events-none"
             style={{
               background: 'linear-gradient(110deg, transparent 30%, rgba(217, 158, 40, 0.03) 50%, transparent 70%)',
               backgroundSize: '200% 100%',
               animation: 'shimmer 3s infinite'
             }} />

        {/* Upload bar */}
        <div className="relative z-10 p-5 border-b backdrop-blur-sm" 
             style={{ 
               borderColor: 'rgba(217, 158, 40, 0.15)',
               background: 'rgba(0, 0, 0, 0.2)'
             }}>
          <div className="flex items-center gap-4">
            <label className="cursor-pointer">
              <input type="file" className="hidden" onChange={onUpload} />
              <span className={`inline-block px-5 py-2.5 rounded-xl border text-xs font-semibold transition-all duration-300 ${uploading?"opacity-60 cursor-not-allowed":"hover:scale-105 hover:shadow-lg active:scale-95"}`}
                    style={{ 
                      borderColor: 'rgba(217, 158, 40, 0.3)',
                      background: 'linear-gradient(135deg, rgba(217, 158, 40, 0.15), rgba(59, 130, 246, 0.1))',
                      color: '#d4af37',
                      boxShadow: '0 0 20px rgba(217, 158, 40, 0.15)'
                    }}>
                {uploading?"Uploading…":"Upload File"}
              </span>
            </label>
            <div className="text-xs ml-auto font-medium" style={{ color: 'rgba(255, 255, 255, 0.4)' }}>
              Folders can't be uploaded or deleted from here.
            </div>
          </div>
        </div>

        {/* Table header */}
        <div className="relative z-10 grid grid-cols-[1fr_120px_160px] px-6 py-3 text-[10px] uppercase tracking-widest font-semibold border-b"
             style={{ 
               color: 'rgba(217, 158, 40, 0.7)', 
               borderColor: 'rgba(217, 158, 40, 0.15)',
               background: 'rgba(0, 0, 0, 0.2)'
             }}>
          <span>Name</span><span>Type</span><span className="text-right">Actions</span>
        </div>

        {/* Table rows */}
        {!project ? (
          <div className="relative z-10 p-12 text-center" style={{ color: 'rgba(255, 255, 255, 0.4)' }}>
            Select a project to view files.
          </div>
        ) : loading ? (
          <div className="relative z-10 p-12 text-center" style={{ color: 'rgba(255, 255, 255, 0.4)' }}>
            Loading…
          </div>
        ) : (
          <div className="relative z-10">
            {path && (
              <div className="grid grid-cols-[1fr_120px_160px] px-6 py-3 border-b cursor-pointer transition-all duration-200"
                   style={{ borderColor: 'rgba(217, 158, 40, 0.1)' }}
                   onMouseEnter={(e) => e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)'}
                   onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
                   onClick={up}>
                <span className="text-white/80">↩️ ..</span>
                <span className="text-white/50">—</span>
                <span />
              </div>
            )}
            {list.length ? list.map((it) => {
              const e = it.isDir ? "" : ext(it.name);
              const tag = it.isDir ? "folder" :
                CODE_EXT.has(e) ? "code" :
                IMG_EXT.has(e)  ? "image" :
                DATA_EXT.has(e) ? "data" :
                DOC_EXT.has(e)  ? "doc" : "file";
              return (
                <div key={`row:${it.name}`}
                     className="grid grid-cols-[1fr_120px_160px] px-6 py-3 border-b transition-all duration-200"
                     style={{ borderColor: 'rgba(217, 158, 40, 0.1)' }}
                     onMouseEnter={(e) => e.currentTarget.style.background = 'rgba(217, 158, 40, 0.05)'}
                     onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                  <div className="flex items-center gap-3 min-w-0">
                    <span className="text-amber-400/70">
                      {it.isDir ? <Icon.Folder/> : <Icon.File/>}
                    </span>
                    {it.isDir ? (
                      <button className="truncate text-left hover:text-amber-300 transition-colors duration-200 text-white/90" 
                              onClick={()=>enter(it.name)}>
                        {it.name.replace(/\/$/, "")}
                      </button>
                    ) : (
                      <button className="truncate text-left hover:text-amber-300 transition-colors duration-200 text-white/90" 
                              onClick={()=>openEditor(it.name)}>
                        {it.name}
                      </button>
                    )}
                  </div>
                  <div className="text-white/60 text-sm font-medium">{tag}</div>
                  <div className="flex items-center justify-end gap-1">
                    {!it.isDir && (
                      <>
                        <IconBtn title="View / Edit" onClick={()=>openEditor(it.name)}><Icon.Edit/></IconBtn>
                        <IconBtn title="Download" onClick={()=>doDownload(it.name)}><Icon.Download/></IconBtn>
                        <IconBtn title="Delete file" className="hover:bg-red-500/10"
                                 onClick={()=>{ if (confirm(`Delete "${it.name}"?`)) doDelete(it.name, it.isDir); }}>
                          <Icon.Trash/>
                        </IconBtn>
                      </>
                    )}
                    {it.isDir && (
                      <IconBtn title="Open" onClick={()=>enter(it.name)}><Icon.ChevronR/></IconBtn>
                    )}
                  </div>
                </div>
              );
            }) : (
              <div className="p-10 text-center text-white/40">This folder is empty.</div>
            )}
          </div>
        )}
      </div>
    </div>

    {/* Monaco Editor Modal */}
    <ModalEditor
      open={modalOpen}
      fileName={modalName}
      language={modalLang}
      value={modalText}
      onChange={setModalText}
      onClose={()=>setModalOpen(false)}
      onSave={saveEditor}
      saving={saving}
    />

    <style jsx>{`
      @keyframes shimmer {
        0% { background-position: -200% 0; }
        100% { background-position: 200% 0; }
      }
      @keyframes float {
        0%, 100% { transform: translateY(0px); }
        50% { transform: translateY(-20px); }
      }
    `}</style>
  </div>
);
}
