// src/api/unittest.js
const BASE = "http://localhost:8082"; // Same as behaviour service port

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

export async function ensureUnitTestProject({ username, projectName }) {
  // Bootstrap unit test project if needed
  return { ok: true, username, projectName };
}

export function generateUnitTests(username, projectName, { brief, files = [] }) {
  const url = `${BASE}/api/unit-tests/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/generate`;
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ brief, files }),
  }).then((r) => toJson(r, url));
}

export function startUnitTestRun({ username, projectName, testType = "unit" }) {
  const url = `${BASE}/api/unit-tests/runs/start`;
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, projectName, testType }),
  }).then((r) => toJson(r, url));
}

export function getUnitTestRunStatus(runId) {
  const url = `${BASE}/api/unit-tests/runs/${encodeURIComponent(runId)}/status`;
  return fetch(url).then((r) => toJson(r, url));
}

export function listUnitTestArtifacts(runId) {
  const url = `${BASE}/api/unit-tests/runs/${encodeURIComponent(runId)}/artifacts`;
  return fetch(url).then((r) => toJson(r, url));
}

export async function saveUnitTestPrompt(username, projectName, message, runId) {
  const url = `${BASE}/api/unit-tests/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/prompts`;
  const res = await fetch(url, {
    method: "POST",
    headers: { 
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*"
    },
    body: JSON.stringify({ message, runId: runId ?? null }),
    credentials: 'include',
    mode: 'cors'
  });
  if (!res.ok) throw new Error((await res.text()) || "Failed to save prompt");
  return res.json();
}

export async function listUnitTestPrompts(username, projectName, limit = 12) {
  // Using the refiner service endpoint with fixed version "v1"
  const versionLabel = "v1"; // Using a fixed version label
  const url = `${BASE}/api/refiner/${encodeURIComponent(username)}/${encodeURIComponent(
    projectName
  )}/${encodeURIComponent(versionLabel)}/prompts?limit=${limit}`;
  try {
    const res = await fetch(url, {
      headers: { 
        "Access-Control-Allow-Origin": "*"
      },
      credentials: 'include',
      mode: 'cors'
    });
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}

export async function refineUnitTests(username, projectName, runId, feedback, autoRun) {
  const url = `${BASE}/api/unit-tests/${encodeURIComponent(username)}/${encodeURIComponent(
    projectName
  )}/refine?runId=${encodeURIComponent(runId)}&autoRun=${autoRun ? "true" : "false"}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userFeedback: feedback || "" }),
  });
  if (!res.ok) throw new Error((await res.text()) || "Refine failed");
  return res.json();
}

export async function generateUnitTestsOnly(username, projectName, brief = "") {
  const url = `${BASE}/api/unit-tests/${encodeURIComponent(username)}/${encodeURIComponent(projectName)}/tests/new`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      brief:
        brief ||
        "Generate comprehensive unit tests for the codebase covering edge cases, boundary conditions, and typical scenarios.",
      activate: true,
      files: [],
    }),
  });
  if (!res.ok) throw new Error((await res.text()) || "Unit test generation failed");
  return res.json();
}

export async function approveUnitTestPlan({ username, projectName }) {
  const driverKey = `${username}/${projectName}/pre-processed/driver.py`;
  const testsKey = `${username}/${projectName}/pre-processed/tests.yaml`;
  const s3Prefix = `s3://my-users-meshops-bucket/${username}/${projectName}/pre-processed`;
  const url = `${BASE}/api/unit-tests/plans/approve`;
  const payload = { username, projectName, driverKey, testsKey, s3Prefix, approved: true };
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error((await res.text()) || "Approve failed");
  return res.json();
}
