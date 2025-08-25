import React, { useEffect, useMemo, useState } from "react";

// text file detection
const TEXT_EXT = new Set([
  "txt","md","csv","json","yaml","yml","py","js","ts","jsx","tsx",
  "html","css","java","xml","sh","properties","ini","conf","cfg"
]);
const ext = (n) => (n.includes(".") ? n.split(".").pop().toLowerCase() : "");
const fmtBytes = (n) => {
  if (n == null || isNaN(n)) return "—";
  const u = ["B","KB","MB","GB","TB"]; let i=0, x=+n; while (x>=1024&&i<u.length-1){x/=1024;i++;} 
  return `${x.toFixed(x<10?1:0)} ${u[i]}`;
};
const Btn = ({ children, ...p }) => (
  <button
    {...p}
    className={
      "px-3 py-1.5 text-xs rounded border border-white/15 hover:bg-white/10 transition " +
      (p.className || "")
    }
  >
    {children}
  </button>
);

export default function StorageView() {
  const username =
    localStorage.getItem("username") ||
    localStorage.getItem("email") ||
    "pg";
  const BASE = "http://localhost:8081";

  // list state
  const [projects, setProjects] = useState([]);
  const [project, setProject]   = useState("");
  const [path, setPath]         = useState("");        // e.g. "raw-data/sub"
  const [items, setItems]       = useState([]);        // ["dir/","file.txt",...]
  const [loading, setLoading]   = useState(false);
  const [uploading, setUploading] = useState(false);

  // preview state
  const [openFile, setOpenFile] = useState("");
  const [isText, setIsText]     = useState(false);
  const [fileText, setFileText] = useState("");
  const [saving, setSaving]     = useState(false);

  // on-screen debug (so you can see exactly what URL/status failed)
  const [status, setStatus]     = useState("ready");
  const setStat = (s) => setStatus(s);

  // helpers
  const api = async (url, init) => {
    setStat(url);
    const res = await fetch(url, init);
    if (!res.ok) {
      const t = await res.text().catch(()=>"");
      const err = new Error(`${res.status} ${res.statusText} @ ${url}\n${t}`);
      // stash raw data for heuristics
      err._status = res.status;
      err._body = t;
      throw err;
    }
    const ct = res.headers.get("content-type") || "";
    if (ct.includes("application/json")) return res.json();
    return res;
  };

  // unified file URL (NO /download)
  const fileURL = (name, useFolder = true) => {
    const u = new URL(
      `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/${encodeURIComponent(name)}`
    );
    if (useFolder && path) u.searchParams.set("folder", path);
    return u.toString();
  };

  // load projects
  const loadProjects = async () => {
    try {
      const url = `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects`;
      const data = await api(url);
      setProjects(Array.isArray(data) ? data : []);
      setStat(`OK (${data.length} projects)`);
    } catch (e) {
      console.error(e);
      setProjects([]);
      setStat(e.message);
    }
  };

  // load files for current folder
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
      setStat(`OK (${Array.isArray(data) ? data.length : 0} items)`);
    } catch (e) {
      console.error(e);
      setItems([]);
      setStat(e.message);
    } finally {
      setLoading(false);
    }
  };

  // effects
  useEffect(() => { loadProjects(); }, []);
  useEffect(() => { setOpenFile(""); setFileText(""); loadFiles(); }, [project, path]);

  // derived
  const list = useMemo(() => {
    const norm = (s) => ({ name: s, isDir: s.endsWith("/") });
    const arr = items.map(norm);
    const dirs = arr.filter(x=>x.isDir).sort((a,b)=>a.name.localeCompare(b.name));
    const files= arr.filter(x=>!x.isDir).sort((a,b)=>a.name.localeCompare(b.name));
    return [...dirs, ...files];
  }, [items]);

  // nav
  const enter = (dir) =>
    setPath(path ? `${path}/${dir.replace(/\/$/, "")}` : dir.replace(/\/$/, ""));
  const up = () => {
    const p = path.split("/").filter(Boolean);
    p.pop();
    setPath(p.join("/"));
  };

  // upload
  const onUpload = async (e) => {
    const file = e.target.files?.[0];
    if (!file || !project) return;
    setUploading(true);
    try {
      const u = new URL(
        `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/upload`
      );
      if (path) u.searchParams.set("folder", path);
      const form = new FormData();
      form.append("file", file);
      await api(u.toString(), { method: "POST", body: form });
      await loadFiles();
    } catch (e2) {
      console.error(e2);
      setStat(e2.message);
    } finally {
      setUploading(false);
      e.target.value = "";
    }
  };

  // Heuristic: if a fetch fails, it might be a folder (backend didn't add "/")
  const looksLikeNoSuchKey = (err) => {
    const body = (err && err._body) || "";
    const code = err && err._status;
    return (
      body.includes("NoSuchKey") ||
      body.includes("The specified key does not exist") ||
      code === 404 || code === 400 || code === 500 // some services 500 on folder
    );
  };

  // download (uses non-/download URL). If it fails, try treating it as a folder.
  const doDownload = async (name) => {
    try {
      const url = fileURL(name);
      const res = await api(url);
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = name;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (e) {
      console.error(e);
      if (looksLikeNoSuchKey(e)) {
        setStat("Looks like a folder — opening instead of downloading…");
        enter(name);
        return;
      }
      setStat(e.message);
    }
  };

  // view/edit (uses non-/download URL). If it fails, treat as folder and enter.
  const openEditor = async (name) => {
    setOpenFile(name);
    setFileText("");
    setIsText(false);
    setStatus("opening…");
    try {
      const url = fileURL(name);
      const res = await api(url);
      const blob = await res.blob();
      if (TEXT_EXT.has(ext(name))) {
        const txt = await blob.text();
        setFileText(txt);
        setIsText(true);
        setStat("editor ready");
      } else {
        setIsText(false);
        setFileText("");
        setStat("preview not supported for binary");
      }
    } catch (e) {
      console.error(e);
      if (looksLikeNoSuchKey(e)) {
        // It’s a folder. Navigate into it.
        setOpenFile("");
        setFileText("");
        setIsText(false);
        setStatus("opening folder…");
        enter(name);
        return;
      }
      setStat(e.message);
    }
  };

  const saveEditor = async () => {
    if (!openFile || !isText) return;
    setSaving(true);
    try {
      const u = new URL(
        `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/upload`
      );
      if (path) u.searchParams.set("folder", path);
      const blob = new Blob([fileText], { type: "text/plain" });
      const file = new File([blob], openFile, { type: "text/plain" });
      const form = new FormData();
      form.append("file", file);
      await api(u.toString(), { method: "POST", body: form });
      setStat("saved");
    } catch (e) {
      console.error(e);
      setStat(e.message);
    } finally {
      setSaving(false);
    }
  };

  // delete
  const doDelete = async (name) => {
    if (!confirm(`Delete "${name}"?`)) return;
    try {
      const u = new URL(
        `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(project)}/delete/${encodeURIComponent(name)}`
      );
      if (path) u.searchParams.set("folder", path);
      await api(u.toString(), { method: "DELETE" });
      await loadFiles();
      if (openFile === name) {
        setOpenFile("");
        setFileText("");
      }
    } catch (e) {
      console.error(e);
      setStat(e.message);
    }
  };

  // UI
  return (
    <div className="min-h-screen text-white grid grid-cols-1 lg:grid-cols-[2fr_1fr]">
      {/* debug/status bar
      <div className="lg:col-span-2 text-xs bg-black/40 text-white/70 px-3 py-1 border-b border-white/10 flex items-center gap-3">
        <span>{status}</span>
        <Btn onClick={loadProjects}>Reload projects</Btn>
        {project && <Btn onClick={() => loadFiles()}>Reload files</Btn>}
      </div> */}

      {/* LEFT: browser */}
      <div className="border-r border-[#2A2A4A]">
        {/* header */}
        <div className="px-6 py-4 border-b border-[#2A2A4A] bg-[#0A0A29] sticky top-0 z-20">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm text-white/70">User</span>
            <span className="px-2 py-1 bg-[#1A1A3B] border border-[#2A2A4A] rounded">
              {username}
            </span>

            <span className="ml-4 text-sm text-white/70">Project</span>
            <select
              className="bg-[#1A1A3B] border border-[#2A2A4A] rounded px-2 py-1"
              value={project}
              onChange={(e) => {
                setProject(e.target.value);
                setPath("");
              }}
            >
              <option value="">Select project…</option>
              {projects.map((p) => (
                <option key={`proj:${p}`} value={p}>
                  {p}
                </option>
              ))}
            </select>

            <Btn
              className="ml-2"
              onClick={async () => {
                const name = prompt("New project name?");
                if (!name) return;
                const url = `${BASE}/api/user-storage/${encodeURIComponent(
                  username
                )}/projects/${encodeURIComponent(name)}`;
                await api(url, { method: "POST" });
                await loadProjects();
                setProject(name);
                setPath("");
              }}
            >
              + New Project
            </Btn>

            {project && (
              <Btn
                onClick={async () => {
                  if (!confirm(`Delete project "${project}" and ALL files?`))
                    return;
                  const url = `${BASE}/api/user-storage/${encodeURIComponent(
                    username
                  )}/projects/${encodeURIComponent(project)}`;
                  await api(url, { method: "DELETE" });
                  await loadProjects();
                  setProject("");
                  setPath("");
                  setItems([]);
                }}
              >
                Delete Project
              </Btn>
            )}

            <div className="ml-auto">
              <label className="cursor-pointer">
                <input type="file" className="hidden" onChange={onUpload} />
                <span
                  className={`px-3 py-2 rounded border border-white/15 hover:bg-white/10 text-xs ${
                    uploading ? "opacity-60 cursor-not-allowed" : ""
                  }`}
                >
                  {uploading ? "Uploading…" : "Upload File"}
                </span>
              </label>
            </div>
          </div>

          {project && (
            <div className="mt-3 text-sm text-white/80 flex items-center gap-2">
              <button className="hover:underline" onClick={() => setPath("")}>
                /{project}
              </button>
              {path
                .split("/")
                .filter(Boolean)
                .map((p, i, arr) => (
                  <React.Fragment key={`crumb:${i}`}>
                    <span className="opacity-40">/</span>
                    <button
                      className="hover:underline"
                      onClick={() => setPath(arr.slice(0, i + 1).join("/"))}
                    >
                      {p}
                    </button>
                  </React.Fragment>
                ))}
              {!!path && <Btn className="ml-2" onClick={up}>Up one level</Btn>}
            </div>
          )}
        </div>

        {/* table header */}
        <div className="grid grid-cols-[1fr_100px_180px_220px] border-b border-[#2A2A4A] bg-[#0A0A29] px-4 py-2 text-xs uppercase tracking-wider text-white/60">
          <span>Name</span><span>Size</span><span>Last modified</span><span>Actions</span>
        </div>

        {/* rows */}
        {!project ? (
          <div className="p-10 text-white/60">Select or create a project to view files.</div>
        ) : loading ? (
          <div className="p-10 text-white/60">Loading…</div>
        ) : (
          <div>
            {path && (
              <div
                className="grid grid-cols-[1fr_100px_180px_220px] px-4 py-2 border-b border-[#2A2A4A] hover:bg-white/5 cursor-pointer"
                onClick={up}
              >
                <span className="text-white/80">↩️ ..</span>
                <span className="text-white/50">—</span>
                <span className="text-white/50">—</span>
                <span />
              </div>
            )}

            {list.length ? (
              list.map((it) => (
                <div
                  key={`row:${it.name}`}
                  className="grid grid-cols-[1fr_100px_180px_220px] px-4 py-2 border-b border-[#2A2A4A] hover:bg-white/5"
                >
                  <div className="flex items-center gap-2">
                    {it.isDir ? "📁" : "📄"}
                    {it.isDir ? (
                      <button
                        className="text-white hover:underline"
                        onClick={() => enter(it.name)}
                      >
                        {it.name.replace(/\/$/, "")}
                      </button>
                    ) : (
                      <button
                        className="text-white hover:underline"
                        onClick={() => openEditor(it.name)}
                      >
                        {it.name}
                      </button>
                    )}
                  </div>
                  <div className="text-white/60">
                    {it.isDir ? "—" : fmtBytes(undefined)}
                  </div>
                  <div className="text-white/60">—</div>
                  <div className="flex items-center justify-end gap-2">
                    {it.isDir ? (
                      <Btn onClick={() => enter(it.name)}>Open</Btn>
                    ) : (
                      <>
                        <Btn onClick={() => openEditor(it.name)}>View / Edit</Btn>
                        <Btn onClick={() => doDownload(it.name)}>Download</Btn>
                        <Btn
                          className="border-red-500/40 hover:bg-red-500/10"
                          onClick={() => doDelete(it.name)}
                        >
                          Delete
                        </Btn>
                      </>
                    )}
                  </div>
                </div>
              ))
            ) : (
              <div className="p-8 text-white/50">This folder is empty.</div>
            )}
          </div>
        )}
      </div>

      {/* RIGHT: inline editor / info */}
      <div className="bg-[#0A0A29] min-h-full">
        <div className="px-4 py-2 text-xs uppercase tracking-wider text-white/60 border-b border-[#2A2A4A]">
          {openFile ? `Preview: ${openFile}` : "DETAILS"}
        </div>

        {openFile ? (
          <div className="p-4 space-y-3">
            {isText ? (
              <>
                <textarea
                  className="w-full h-[60vh] bg-black/40 border border-white/10 rounded p-3 font-mono text-sm"
                  value={fileText}
                  onChange={(e) => setFileText(e.target.value)}
                />
                <div className="flex items-center gap-2">
                  <Btn onClick={saveEditor} className={saving ? "opacity-60 cursor-not-allowed" : ""}>
                    {saving ? "Saving…" : "Save"}
                  </Btn>
                  <Btn onClick={() => { setOpenFile(""); setFileText(""); }}>Close</Btn>
                </div>
                <p className="text-xs text-white/50">
                  Saving overwrites <span className="text-white">{openFile}</span>{" "}
                  in /{project}{path ? "/" + path : ""}.
                </p>
              </>
            ) : (
              <div className="text-white/70 text-sm">
                No inline preview for this file type. Use <b>Download</b>.
              </div>
            )}
          </div>
        ) : (
          <div className="p-4 text-sm text-white/70">Pick a file to view/edit it here.</div>
        )}
      </div>
    </div>
  );
}
