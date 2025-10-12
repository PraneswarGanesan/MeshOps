// src/api/retrain.js
const BASE = "http://localhost:8000"; // RetrainService FastAPI port

async function toJson(res, url) {
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  if (!res.ok) {
    const msg = (json && (json.message || json.error || json.detail)) || text || `Failed @ ${url}`;
    const err = new Error(msg);
    err.status = res.status;
    err.body = text;
    err.json = json;
    throw err;
  }
  return json ?? text;
}

export async function startRetrainJob({ username, projectName, files, saveBase, version, requirementsPath, extraArgs }) {
  const url = `${BASE}/retrain`;
  const payload = {
    username,
    projectName,
    files: files || [],
    saveBase,
    version: version || null,
    requirementsPath: requirementsPath || null,
    extraArgs: extraArgs || []
  };
  
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  
  return toJson(res, url);
}

export async function getRetrainStatus(jobId) {
  const url = `${BASE}/status/${encodeURIComponent(jobId)}`;
  const res = await fetch(url);
  return toJson(res, url);
}

export async function getRetrainConsole(jobId) {
  const url = `${BASE}/console/${encodeURIComponent(jobId)}`;
  const res = await fetch(url);
  return toJson(res, url);
}

// Helper function to construct S3 save base path
export function buildSaveBase(username, projectName, version = null) {
  const versionPath = version ? `versions/${version}` : `versions/v1`;
  return `${username}/${projectName}/artifacts/${versionPath}`;
}

// Helper function to construct file paths for S3
export function buildFilePaths(username, projectName, fileNames, folder = "pre-processed") {
  return fileNames.map(fileName => `${username}/${projectName}/${folder}/${fileName}`);
}
