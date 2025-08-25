// src/pages/PreProcess.jsx
import React, { useEffect, useMemo, useState } from "react";
import { PreprocessAPI } from "../api/preprocessing"; // uses baseURL http://localhost:8081 (your current file)

// If you already have a central StorageAPI, feel free to replace the two
// helper fns below with StorageAPI.listProjects(...) and StorageAPI.listFiles(...).

const BASE_STORAGE = "http://localhost:8081";

const Btn = ({ className = "", ...p }) => (
  <button
    {...p}
    className={`px-3 py-2 text-xs rounded border border-white/15 hover:bg-white/10 transition ${className}`}
  />
);

export default function PreProcess() {
  const username =
    localStorage.getItem("username") ||
    localStorage.getItem("email") ||
    "pg";

  // ---- state ----
  const [projects, setProjects] = useState([]);
  const [project, setProject] = useState("");
  const [folder, setFolder] = useState("raw-data"); // sensible default

  const [allItems, setAllItems] = useState([]); // files only
  const [checked, setChecked] = useState({});   // { "fileA.csv": true }

  const [loadingFiles, setLoadingFiles] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("ready");
  const [result, setResult] = useState("");

  // ---- helpers to hit storage endpoints directly ----
  const listProjects = async () => {
    const url = `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(username)}/projects`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Projects: ${res.status} ${res.statusText}`);
    return (await res.json()) ?? [];
  };

  const listFiles = async (user, pjt, fld = "") => {
    const u = new URL(
      `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(user)}/projects/${encodeURIComponent(pjt)}/files`
    );
    if (fld) u.searchParams.set("folder", fld);
    const res = await fetch(u.toString());
    if (!res.ok) throw new Error(`Files: ${res.status} ${res.statusText}`);
    return (await res.json()) ?? [];
  };

  // ---- effects ----
  useEffect(() => {
    (async () => {
      try {
        const projs = await listProjects();
        setProjects(projs);
      } catch (e) {
        console.error(e);
        setStatus("Failed to load projects");
      }
    })();
  }, []); // once

  const loadFolder = async () => {
    if (!project) return;
    setLoadingFiles(true);
    setStatus("Loading folder…");
    setAllItems([]);
    setChecked({});
    try {
      const items = await listFiles(username, project, folder || "");
      // Only let users choose files (ignore directories that end with "/")
      const files = (items || []).filter((n) => !n.endsWith("/"));
      setAllItems(files);
      setStatus(`Loaded ${files.length} files`);
    } catch (e) {
      console.error(e);
      setStatus("Failed to list files");
    } finally {
      setLoadingFiles(false);
    }
  };

  useEffect(() => {
    // auto-refresh when project/folder changes (small debounce)
    const t = setTimeout(() => {
      if (project) loadFolder();
    }, 200);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project, folder]);

  // ---- derived ----
  const selectedFiles = useMemo(
    () => allItems.filter((f) => checked[f]),
    [allItems, checked]
  );

  // ---- actions ----
  const toggleAll = (val) => {
    const next = {};
    allItems.forEach((f) => (next[f] = val));
    setChecked(next);
  };

  const trigger = async () => {
    if (!project) {
      setStatus("Pick a project first");
      return;
    }
    setBusy(true);
    setStatus("Triggering…");
    setResult("");
    try {
      // If none selected, send null to process whole folder
      const body = selectedFiles.length ? selectedFiles : null;
      const res = await PreprocessAPI.trigger(
        username,
        project,
        folder || "",
        body
      );

      // Normalize response to pretty string
      let txt = "";
      if (typeof res.data === "string") {
        txt = res.data;
      } else {
        txt = JSON.stringify(res.data, null, 2);
      }
      setResult(txt);
      setStatus("Done");
    } catch (e) {
      console.error(e);
      setStatus("Failed to trigger");
      const msg =
        e?.response?.data
          ? typeof e.response.data === "string"
            ? e.response.data
            : JSON.stringify(e.response.data, null, 2)
          : e.message;
      setResult(msg);
    } finally {
      setBusy(false);
    }
  };

  // ---- UI ----
  return (
    <div className="min-h-screen text-white">
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
            onChange={(e) => setProject(e.target.value)}
          >
            <option value="">Select project…</option>
            {projects.map((p) => (
              <option key={`proj:${p}`} value={p}>
                {p}
              </option>
            ))}
          </select>

          <span className="ml-4 text-sm text-white/70">Folder</span>
          <input
            className="bg-[#1A1A3B] border border-[#2A2A4A] rounded px-2 py-1 w-64"
            placeholder="e.g. raw-data or raw-data/images"
            value={folder}
            onChange={(e) => setFolder(e.target.value.replace(/^\//, ""))}
          />

          <Btn onClick={loadFolder} className="ml-2">
            Refresh Files
          </Btn>

          <div className="ml-auto text-xs text-white/60">{status}</div>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[2fr_1fr]">
        {/* left: files to select */}
        <div className="p-4 border-r border-[#2A2A4A]">
          <div className="flex items-center gap-2 mb-3">
            <Btn onClick={() => toggleAll(true)}>Select all</Btn>
            <Btn onClick={() => toggleAll(false)}>Clear</Btn>
            <span className="text-white/60 text-sm">
              {selectedFiles.length} selected / {allItems.length} files
            </span>
          </div>

          {loadingFiles ? (
            <div className="text-white/60 p-4">Loading files…</div>
          ) : allItems.length ? (
            <div className="max-h-[60vh] overflow-auto rounded border border-white/10">
              {allItems.map((f) => (
                <label
                  key={f}
                  className="flex items-center gap-3 px-3 py-2 border-b border-white/5 hover:bg-white/5"
                >
                  <input
                    type="checkbox"
                    checked={!!checked[f]}
                    onChange={(e) =>
                      setChecked((prev) => ({ ...prev, [f]: e.target.checked }))
                    }
                  />
                  <span className="font-mono text-sm">{f}</span>
                </label>
              ))}
            </div>
          ) : (
            <div className="text-white/60 p-4">No files in this folder.</div>
          )}
        </div>

        {/* right: actions + result */}
        <div className="p-4">
          <div className="space-y-3">
            <Btn onClick={trigger} className={busy ? "opacity-60" : ""} disabled={busy || !project}>
              {busy ? "Triggering…" : "Run Pre-Process"}
            </Btn>
            <div className="text-xs text-white/60">
              Tip: leave all checkboxes unchecked to process the <b>entire folder</b>.
            </div>
          </div>

          <div className="mt-4">
            <div className="text-xs uppercase tracking-wider text-white/60 mb-2">
              Result
            </div>
            <pre className="bg-black/40 border border-white/10 rounded p-3 text-xs max-h-[50vh] overflow-auto whitespace-pre-wrap">
{result || "—"}
            </pre>
          </div>
        </div>
      </div>
    </div>
  );
}
