// src/pages/PreProcess.jsx
import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import { PreprocessAPI } from "../api/preprocessing";

const BASE_STORAGE = "http://localhost:8081";

// --- Theme tokens (gold/black/white) ---
const COLORS = {
  bg: "#0B0B12",
  panel: "#0F0F1A",
  panelAlt: "#101622",
  border: "rgba(255,255,255,0.08)",
  soft: "rgba(255,255,255,0.06)",
  textSoft: "rgba(255,255,255,0.65)",
  gold: "#D4AF37",
  goldSoft: "#B69121",
  goldGlow: "rgba(212,175,55,0.35)",
};

// --- Small UI atoms (polished, low-friction) ---
const Pill = ({ children, className = "", ...p }) => (
  <span
    {...p}
    className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs tracking-wide border ${className}`}
    style={{ borderColor: COLORS.border, background: COLORS.panelAlt, color: COLORS.textSoft }}
  >
    {children}
  </span>
);

const GButton = ({ children, className = "", glow = false, ...p }) => (
  <button
    {...p}
    className={`relative group px-4 py-2 rounded-xl text-sm font-medium transition-all border focus:outline-none ${className}`}
    style={{
      borderColor: glow ? COLORS.goldSoft : COLORS.border,
      background: `linear-gradient(180deg, rgba(255,255,255,0.03), rgba(255,255,255,0.015))`,
      color: "white",
      boxShadow: glow
        ? `0 0 0px 0 ${COLORS.goldGlow}, inset 0 0 0 1px ${COLORS.soft}`
        : `inset 0 0 0 1px ${COLORS.soft}`,
    }}
    onMouseEnter={(e) => {
      if (!glow) return;
      e.currentTarget.style.boxShadow = `0 6px 26px 0 ${COLORS.goldGlow}, inset 0 0 0 1px ${COLORS.soft}`;
    }}
    onMouseLeave={(e) => {
      if (!glow) return;
      e.currentTarget.style.boxShadow = `0 0 0px 0 ${COLORS.goldGlow}, inset 0 0 0 1px ${COLORS.soft}`;
    }}
  >
    <span
      className="absolute inset-0 rounded-xl opacity-0 group-hover:opacity-100 transition"
      style={{ background: "linear-gradient(90deg, rgba(212,175,55,0.10), transparent)" }}
    />
    <span className="relative z-10">{children}</span>
  </button>
);

const Field = ({ children, label }) => (
  <label className="block">
    <div className="mb-1 text-[11px] uppercase tracking-[0.12em]" style={{ color: COLORS.textSoft }}>{label}</div>
    {children}
  </label>
);

// Modern chevron-styled Select (uses native select for a11y, but looks fresh)
const Select = ({ className = "", style = {}, ...props }) => (
  <div className={`relative ${className}`}>
    <select
      {...props}
      className="w-full appearance-none rounded-xl px-3 py-2 pr-10 bg-transparent border outline-none transition focus:shadow-[0_0_0_3px_rgba(212,175,55,0.25)]"
      style={{ borderColor: COLORS.border, color: "white", ...style }}
    />
    <svg
      className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 opacity-70"
      width="16" height="16" viewBox="0 0 24 24" fill="none"
    >
      <path d="M7 10l5 5 5-5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  </div>
);

// Smooth gold Toggle
const Toggle = ({ checked, onChange }) => (
  <label className="inline-flex items-center cursor-pointer select-none">
    <input type="checkbox" className="sr-only" checked={checked} onChange={onChange} />
    <span className="relative w-12 h-7 rounded-full transition-all" style={{
      background: checked
        ? `linear-gradient(180deg, ${COLORS.gold}, ${COLORS.goldSoft})`
        : `linear-gradient(180deg, rgba(255,255,255,0.07), rgba(255,255,255,0.03))`,
      boxShadow: checked ? `0 0 0 3px ${COLORS.goldGlow}` : `inset 0 0 0 1px ${COLORS.soft}`,
    }}>
      <span className="absolute top-1 left-1 h-5 w-5 rounded-full bg-white transition-transform" style={{ transform: checked ? 'translateX(20px)' : 'translateX(0)' }} />
    </span>
  </label>
);

// Skeleton shimmer
const Skeleton = ({ className = "" }) => (
  <div className={`overflow-hidden relative ${className}`} style={{ background: COLORS.soft }}>
    <div className="absolute inset-0 -translate-x-full animate-[shimmer_1.1s_infinite] bg-gradient-to-r from-transparent via-white/10 to-transparent"/>
  </div>
);

export default function PreProcess() {
  const username = localStorage.getItem("username") || localStorage.getItem("email") || "pg";

  // state
  const [projects, setProjects] = useState([]);
  const [project, setProject] = useState("");
  const [folder, setFolder] = useState("raw-data");
  const [allItems, setAllItems] = useState([]);
  const [checked, setChecked] = useState({});
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("Ready");
  const [result, setResult] = useState("");
  const [query, setQuery] = useState("");
  const [compact, setCompact] = useState(true); // NEW: tighter row density toggle

  // --- storage helpers ---
  const listProjects = async () => {
    const url = `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(username)}/projects`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Projects: ${res.status} ${res.statusText}`);
    return (await res.json()) ?? [];
  };
  const listFiles = async (user, pjt, fld = "") => {
    const u = new URL(`${BASE_STORAGE}/api/user-storage/${encodeURIComponent(user)}/projects/${encodeURIComponent(pjt)}/files`);
    if (fld) u.searchParams.set("folder", fld);
    const res = await fetch(u.toString());
    if (!res.ok) throw new Error(`Files: ${res.status} ${res.statusText}`);
    return (await res.json()) ?? [];
  };

  // effects
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
  }, []);

  const loadFolder = async () => {
    if (!project) return;
    setLoadingFiles(true);
    setStatus("Loading folder…");
    setAllItems([]);
    setChecked({});
    try {
      const items = await listFiles(username, project, folder || "");
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
    const t = setTimeout(() => { if (project) loadFolder(); }, 200);
    return () => clearTimeout(t);
  }, [project, folder]);

  // derived
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return allItems;
    return allItems.filter((f) => f.toLowerCase().includes(q));
  }, [allItems, query]);
  const selectedFiles = useMemo(() => allItems.filter((f) => checked[f]), [allItems, checked]);

  // actions
  const toggleAll = (val, list = filtered) => {
    const next = { ...checked };
    list.forEach((f) => (next[f] = val));
    setChecked(next);
  };

  const quickSelect = (ext) => {
    const match = filtered.filter((f) => f.toLowerCase().endsWith(ext));
    toggleAll(true, match);
  };

  const trigger = async () => {
    if (!project) { setStatus("Pick a project first"); return; }
    setBusy(true);
    setStatus("Triggering…");
    setResult("");
    try {
      const body = selectedFiles.length ? selectedFiles : null;
      const res = await PreprocessAPI.trigger(username, project, folder || "", body);
      const txt = typeof res.data === "string" ? res.data : JSON.stringify(res.data, null, 2);
      setResult(txt);
      setStatus("Done");
    } catch (e) {
      console.error(e);
      setStatus("Failed to trigger");
      const msg = e?.response?.data
        ? typeof e.response.data === "string" ? e.response.data : JSON.stringify(e.response.data, null, 2)
        : e.message;
      setResult(msg);
    } finally {
      setBusy(false);
    }
  };

  // --- UI ---
  return (
    <div className="min-h-screen" style={{ background: COLORS.bg }}>
      {/* Ambient gold glow ring */}
      <div className="pointer-events-none fixed inset-0 blur-3xl opacity-40" style={{
        background: `radial-gradient(600px 300px at 85% 10%, ${COLORS.goldGlow}, transparent), radial-gradient(400px 220px at 10% 40%, rgba(255,255,255,0.05), transparent)`
      }}/>

      {/* Header / Hero */}
      <div className="sticky top-0 z-30 border-b backdrop-blur" style={{ borderColor: COLORS.border, background: "rgba(10,10,18,0.65)" }}>
        <div className="mx-auto max-w-7xl px-6 py-4 flex items-center gap-4">
          <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }} className="flex-1">
            <div className="flex items-center gap-3">
              <Pill>Signed in as <b className="ml-1 text-white">{username}</b></Pill>
              <Pill>Pre‑Process Studio</Pill>
              <span className="ml-auto text-xs" style={{ color: COLORS.textSoft }}>{status}</span>
            </div>
            <div className="mt-3 flex flex-wrap items-end gap-4">
              <h1 className="text-3xl md:text-4xl font-semibold tracking-tight text-white">Pre- Processing</h1>
              <div className="h-[1px] flex-1" style={{ background: `linear-gradient(90deg, ${COLORS.goldGlow}, transparent)` }}/>
            </div>
          </motion.div>
        </div>
      </div>

      <div className="mx-auto max-w-7xl px-6 py-6 grid gap-6 lg:grid-cols-[1.7fr_1fr]">
        {/* LEFT: file explorer */}
        <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35 }}
          className="rounded-2xl border shadow-sm overflow-hidden"
          style={{ borderColor: COLORS.border, background: COLORS.panel }}>
          {/* Controls */}
          <div className="p-4 border-b" style={{ borderColor: COLORS.border }}>
            <div className="grid md:grid-cols-3 gap-3">
              <Field label="Project">
                <Select value={project} onChange={(e) => setProject(e.target.value)}>
                  <option value="" className="bg-black">Select project…</option>
                  {projects.map((p) => (
                    <option key={`proj:${p}`} value={p} className="bg-black">{p}</option>
                  ))}
                </Select>
              </Field>
              <Field label="Folder">
                <input
                  className="w-full rounded-xl px-3 py-2 bg-transparent border outline-none transition focus:shadow-[0_0_0_3px_rgba(212,175,55,0.25)]"
                  style={{ borderColor: COLORS.border, color: "white" }}
                  placeholder="e.g. raw-data or raw-data/images"
                  value={folder}
                  onChange={(e) => setFolder(e.target.value.replace(/^\//, ""))}
                />
              </Field>
              <Field label="Search files">
                <input
                  className="w-full rounded-xl px-3 py-2 bg-transparent border outline-none transition focus:shadow-[0_0_0_3px_rgba(212,175,55,0.25)]"
                  style={{ borderColor: COLORS.border, color: "white" }}
                  placeholder="Type to filter…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
              </Field>
            </div>
            <div className="mt-4 flex flex-wrap items-center gap-2">
              <GButton onClick={loadFolder}>Refresh</GButton>
              <GButton onClick={() => toggleAll(true)}>Select all</GButton>
              <GButton onClick={() => toggleAll(false)}>Clear</GButton>
              <GButton onClick={() => quickSelect('.csv')}>Pick *.csv</GButton>
              <GButton onClick={() => quickSelect('.json')}>Pick *.json</GButton>
              <div className="ml-auto flex items-center gap-2 text-xs" style={{ color: COLORS.textSoft }}>
                Compact rows
                <Toggle checked={compact} onChange={(e) => setCompact(e.target.checked)} />
              </div>
            </div>
          </div>

          {/* File list */}
          <div className="max-h-[56vh] overflow-auto">
            {loadingFiles ? (
              <div className="p-4 space-y-2">
                {Array.from({ length: 8 }).map((_, i) => (
                  <Skeleton key={i} className="h-10 rounded-lg"/>
                ))}
              </div>
            ) : filtered.length ? (
              <ul className="divide-y" style={{ borderColor: COLORS.border }}>
                {filtered.map((f) => (
                  <li
                    key={f}
                    className={`flex items-center gap-3 px-4 ${compact ? 'py-2.5' : 'py-3.5'} hover:bg-white/5 transition`}
                  >
                    <input
                      type="checkbox"
                      className="accent-[#D4AF37] scale-110"
                      checked={!!checked[f]}
                      onChange={(e) => setChecked((prev) => ({ ...prev, [f]: e.target.checked }))}
                    />
                    <span className="font-mono text-sm text-white flex-1 truncate">{f}</span>
                    <span className="text-[11px]" style={{ color: COLORS.textSoft }}>{f.split('/').slice(-1)[0]}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="p-6 text-center" style={{ color: COLORS.textSoft }}>
                No files found in this folder.
              </div>
            )}
          </div>
        </motion.div>

        {/* RIGHT: actions + result / insight */}
        <motion.div initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }} className="space-y-6">
          {/* Stats */}
          <div className="grid grid-cols-3 gap-3">
            {[{label: 'Projects', value: projects.length}, {label:'Files', value: allItems.length}, {label:'Selected', value: selectedFiles.length}].map((s) => (
              <div key={s.label} className="rounded-2xl p-4 border relative overflow-hidden" style={{ borderColor: COLORS.border, background: COLORS.panel }}>
                <div className="absolute -top-6 -right-6 w-20 h-20 rounded-full opacity-20" style={{ background: `radial-gradient(closest-side, ${COLORS.goldGlow}, transparent)` }}/>
                <div className="text-xs uppercase tracking-wider" style={{ color: COLORS.textSoft }}>{s.label}</div>
                <div className="mt-1 text-2xl font-semibold text-white">{s.value}</div>
              </div>
            ))}
          </div>

          {/* Action Card */}
          <div className="rounded-2xl border p-5" style={{ borderColor: COLORS.border, background: COLORS.panel }}>
            <div className="flex flex-wrap items-center gap-3">
              <GButton glow disabled={busy || !project} aria-busy={busy} onClick={trigger}>
                {busy ? "Processing…" : "Run Pre‑Process"}
              </GButton>
              <span className="text-xs" style={{ color: COLORS.textSoft }}>
                Tip: leave all checkboxes off to process the <b>entire folder</b>.
              </span>
            </div>
            <div className="mt-4">
              <div className="text-[11px] uppercase tracking-[0.12em] mb-2" style={{ color: COLORS.textSoft }}>Result</div>
              <pre className="rounded-xl p-3 text-[12px] leading-relaxed max-h-[44vh] overflow-auto border"
                   style={{ background: COLORS.panelAlt, borderColor: COLORS.border, color: "#EDEDED" }}>
{result || "—"}
              </pre>
            </div>
          </div>

          {/* Activity / Hints */}
          <div className="rounded-2xl border p-5" style={{ borderColor: COLORS.border, background: COLORS.panel }}>
            <div className="text-[11px] uppercase tracking-[0.12em] mb-2" style={{ color: COLORS.textSoft }}>Pro tips</div>
            <ul className="space-y-2 text-sm" style={{ color: COLORS.textSoft }}>
              <li>Use the search box to quickly filter to <span className="text-white">.csv</span>, <span className="text-white">.json</span>, etc.</li>
              <li>Quick‑select buttons help you target by extension in one click.</li>
              <li>Results are preserved until your next run—copy them directly from the pane.</li>
            </ul>
          </div>
        </motion.div>
      </div>

      {/* Footer line */}
      <div className="mx-auto max-w-7xl px-6 pb-10">
        <div className="h-px w-full" style={{ background: `linear-gradient(90deg, transparent, ${COLORS.goldGlow}, transparent)` }}/>
      </div>

      <style>{`
        @keyframes shimmer { 100% { transform: translateX(100%); } }
      `}</style>
    </div>
  );
}
