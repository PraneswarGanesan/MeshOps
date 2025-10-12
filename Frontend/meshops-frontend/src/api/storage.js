// src/api/storage.js
const BASE = "http://localhost:8081";
const PROJECTS_BASE = "http://localhost:8082"; // for MySQL project ensure

async function ok(res, url) {
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    const err = new Error(`${res.status} ${res.statusText} @ ${url}\n${body}`);
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return res;
}

export const StorageAPI = {
  async listProjects(username) {
    const url = `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects`;
    const res = await ok(await fetch(url), url);
    return res.json(); // ["projectA","projectB"]
  },

  async createProject(username, projectName) {
    // Creates the S3 folder for a project
    const url = `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}`;
    const res = await ok(await fetch(url, { method: "POST" }), url);
    // backend may return plain text like "Project created successfully"
    try { return await res.text(); } catch { return "ok"; }
  },

  async listFiles(username, projectName, folder = "") {
    const u = new URL(
      `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/files`
    );
    if (folder) u.searchParams.set("folder", folder);
    const url = u.toString();
    const res = await ok(await fetch(url), url);
    return res.json(); // ["driver.py","tests.yaml","subdir/"]
  },

  async fetchTextFile(username, projectName, fileName, folder = "") {
    const u = new URL(
      `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/download/${encodeURIComponent(fileName)}`
    );
    if (folder) u.searchParams.set("folder", folder);
    const url = u.toString();
    const res = await ok(await fetch(url), url);
    return res.text();
  },

  async uploadTextFile(username, projectName, fileName, content, folder = "", contentType = "text/plain") {
    const u = new URL(
      `${BASE}/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/upload`
    );
    if (folder) u.searchParams.set("folder", folder);
    const url = u.toString();

    const blob = new Blob([content], { type: contentType });
    const file = new File([blob], fileName, { type: contentType });
    const form = new FormData();
    form.append("file", file);

    const res = await ok(await fetch(url, { method: "POST", body: form }), url);
    try { return await res.json(); } catch { return true; }
  },

  async ensureProject(username, projectName, bucket = "my-users-meshops-bucket") {
    // Ensures the project is recorded in MySQL
    const s3Prefix = `s3://${bucket}/${username}/${projectName}/`;
    const url = `${PROJECTS_BASE}/api/projects/ensure`;
    const payload = { username, projectName, s3Prefix };
    console.log("[ensureProject] payload:", payload);
    const res = await ok(
      await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
      url
    );
    try { return await res.json(); } catch { return true; }
  },
};
