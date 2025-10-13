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

export function approvePlan({ username, projectName, versionLabel, driverKey, testsKey, approved = true }) {
  // POST /api/plans/approve with required payload
  const url = `${BASE}/api/plans/approve`;
  const payload = { username, projectName, versionLabel, driverKey, testsKey, approved };
  console.log("[approvePlan] POST", url, payload);
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }).then((r) => toJson(r, url));
}

export function startRun({ username, projectName, versionName, task }) {
  const url = `${BASE}/api/runs/start`;
  const payload = { username, projectName, versionName, task };
  console.log("[startRun] POST", url, payload);
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  })
    .then((r) => toJson(r, url))
    .then((res) => {
      const runId = res?.data?.runId ?? res?.runId;
      console.log("[startRun] response", res, "runId:", runId);
      return res;
    });
}

export function getRunStatus(runId) {
  const url = `${BASE}/api/runs/${encodeURIComponent(runId)}/status`;
  return fetch(url).then((r) => toJson(r, url));
}

export function listArtifacts(runId) {
  const url = `${BASE}/api/runs/${encodeURIComponent(runId)}/artifacts`;
  return fetch(url).then((r) => toJson(r, url));
}

export function generateDriverAndTests(username, projectName, versionLabel, brief) {
  // POST /api/plans/{u}/{p}/{v}/generate with { brief }
  const url = `${BASE}/api/plans/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/${encodeURIComponent(versionLabel)}/generate`;
  console.log("[generateDriverAndTests] POST", url, { brief });
  return fetch(url, {
    method: "POST",
    headers: { 
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*"
    },
    body: JSON.stringify({ brief }),
    credentials: 'include',
    mode: 'cors'
  }).then((r) => toJson(r, url));
}

// Direct endpoint for cat_dog_im v2 generation
export function generateCatDogClassifier(brief) {
  const url = `${BASE}/api/plans/pg/cat_dog_im/v2/generate`;
  console.log("[generateCatDogClassifier] POST", url, { brief });
  return fetch(url, {
    method: "POST",
    headers: { 
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*"
    },
    body: JSON.stringify({ brief }),
    credentials: 'include',
    mode: 'cors'
  }).then((r) => toJson(r, url));
}

export function refineByPromptId(username, projectName, versionLabel, promptId) {
  const url = `${BASE}/api/refiner/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/${encodeURIComponent(versionLabel)}/refine?promptId=${encodeURIComponent(promptId)}&autoRun=false`;
  console.log("[refineByPromptId] POST", url);
  return fetch(url, { method: "POST" }).then((r) => toJson(r, url));
}


