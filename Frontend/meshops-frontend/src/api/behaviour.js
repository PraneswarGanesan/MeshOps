// src/api/behaviour.js
const BASE = "http://localhost:8082";

async function toJson(res, url) {
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  if (!res.ok) {
    const msg = (json && (json.message || json.error)) || text || `Failed @ ${url}`;
    const err = new Error(msg);
    err.status = res.status;
    err.body = text;
    err.json = json;
    throw err;
  }
  return json ?? text;
}

export async function ensureProject({ username, projectName }) {
  // No-op (hook up to your bootstrap endpoint if you have one)
  return { ok: true, username, projectName };
}

export function generatePlan(username, projectName, { brief, files = [] }) {
  const url = `${BASE}/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/new`;
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ brief, files }),
  }).then((r) => toJson(r, url));
}

export function approvePlan({ username, projectName, approved = true }) {
  const url = `${BASE}/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/approve`;
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ approved }),
  }).then((r) => toJson(r, url));
}

export function startRun({ username, projectName, task }) {
  const url = `${BASE}/api/runs/start`;
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, projectName, task }),
  }).then((r) => toJson(r, url));
}

export function getRunStatus(runId) {
  const url = `${BASE}/api/runs/${encodeURIComponent(runId)}/status`;
  return fetch(url).then((r) => toJson(r, url));
}

export function listArtifacts(runId) {
  const url = `${BASE}/api/runs/${encodeURIComponent(runId)}/artifacts`;
  return fetch(url).then((r) => toJson(r, url));
}
