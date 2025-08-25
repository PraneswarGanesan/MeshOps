// src/api/behaviour.js
import axios from "axios";

const API = axios.create({
  baseURL: "http://localhost:8082",
  headers: { "Content-Type": "application/json" },
});

API.interceptors.request.use((cfg) => {
  const token = localStorage.getItem("token");
  if (token) cfg.headers.Authorization = `Bearer ${token}`;
  return cfg;
});

// -------- Projects --------
export const ensureProject = (req /* {username, projectName, s3Prefix} */) =>
  API.post("/api/projects/ensure", req);

// -------- Plans --------
export const generatePlan = (username, projectName, req /* {brief, files[]} */) =>
  API.post(`/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/generate`, req);

export const approvePlan = (req /* {username, projectName, driverKey, testsKey, s3Prefix, approved} */) =>
  API.post("/api/plans/approve", req);

// NEW: list all tests (canonical + versions)
export const listTests = (username, projectName) =>
  API.get(`/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/tests`);

// NEW: generate a fresh, versioned tests.yaml (driver not touched)
// req: { brief, files, s3Prefix?, versionLabel?, activate? }
// NEW: generate a fresh, versioned tests.yaml
export const generateTestsNow = (username, projectName, req) =>
  API.post(`/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/tests/new`, req);


// NEW: activate a chosen version as canonical tests.yaml
// req: { key, s3Prefix? }
export const activateTests = (username, projectName, req) =>
  API.post(`/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/tests/activate`, req);

// -------- Runs --------
export const startRun = (req /* {username, projectName, task, instanceId?} */) =>
  API.post("/api/runs/start", req);

export const getRunStatus = (runId) =>
  API.get(`/api/runs/${encodeURIComponent(runId)}/status`);

export const pollRun = (runId) =>
  API.post(`/api/runs/${encodeURIComponent(runId)}/poll`);

export const listArtifacts = (runId) =>
  API.get(`/api/runs/${encodeURIComponent(runId)}/artifacts`);

// OPTIONAL (if you want console in UI later)
// export const getConsole = (runId) =>
//   API.get(`/api/runs/${encodeURIComponent(runId)}/console`, { responseType: "text" });
// --- add this helper ---
function normalizeKey(key) {
  if (!key) return "";
  // strip leading "s3://bucket/"
  const m = /^s3:\/\/[^/]+\/(.+)$/.exec(key);
  if (m) return m[1];
  // strip accidental leading slash
  if (key.startsWith("/")) return key.slice(1);
  return key;
}

function splitKey(key) {
  const cleaned = normalizeKey(key);
  const parts = cleaned.split("/").filter(Boolean);
  const fileName = parts.pop() || "";
  const folder = parts.join("/");
  return { folder, fileName };
}

async function downloadS3Text(username, projectName, key) {
  const { folder, fileName } = splitKey(key);
  const url = `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/download/${encodeURIComponent(fileName)}?folder=${encodeURIComponent(folder)}`;
  const r = await fetch(url);
  if (!r.ok) throw new Error("download failed: " + r.status);
  return await r.text();
}

async function uploadS3Text(username, projectName, key, content, contentType = "text/plain") {
  const { folder, fileName } = splitKey(key);
  const url = `${BASE_STORAGE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/upload/${encodeURIComponent(fileName)}?folder=${encodeURIComponent(folder)}`;
  const r = await fetch(url, { method: "PUT", headers: { "Content-Type": contentType }, body: content });
  if (!r.ok) throw new Error("upload failed: " + r.status);
}
