// src/api/storage.js
import axios from "axios";

const API = axios.create({
  baseURL: "http://localhost:8081",
  headers: { "Content-Type": "application/json" },
});

API.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

export const StorageAPI = {
  /* -------- Projects -------- */
  createProject: (username, projectName) =>
    API.post(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}`
    ),

  listProjects: async (username) => {
    const res = await API.get(
      `/api/user-storage/${encodeURIComponent(username)}/projects`
    );
    return Array.isArray(res.data) ? res.data : [];
  },

  deleteProject: (username, projectName) =>
    API.delete(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}`
    ),

  /* -------- Files / folders -------- */
  listFiles: async (username, projectName, folder = "") => {
    const res = await API.get(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/files`,
      { params: { folder: folder || undefined } }
    );
    return Array.isArray(res.data) ? res.data : [];
  },

  // Upload a binary file (multipart)
  uploadFile: async (username, projectName, file, folder = "") => {
    const form = new FormData();
    form.append("file", file);
    await API.post(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/upload`,
      form,
      {
        params: { folder: folder || undefined },
        headers: { "Content-Type": "multipart/form-data" },
      }
    );
  },

  // Download file as a browser download (blob -> save)
  downloadFile: async (username, projectName, fileName, folder = "") => {
    const res = await API.get(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/download/${encodeURIComponent(fileName)}`,
      { params: { folder: folder || undefined }, responseType: "blob" }
    );
    const url = URL.createObjectURL(res.data);
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  },

  deleteFile: (username, projectName, fileName, folder = "") =>
    API.delete(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/delete/${encodeURIComponent(fileName)}`,
      { params: { folder: folder || undefined } }
    ),

  /* -------- TEXT helpers used by BehaviourTest.jsx -------- */

  // Fetch a file's content as TEXT (no download prompt)
  fetchTextFile: async (username, projectName, fileName, folder = "") => {
    const res = await API.get(
      `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/download/${encodeURIComponent(fileName)}`,
      { params: { folder: folder || undefined }, responseType: "text" }
    );
    // axios with responseType:"text" puts the string in res.data
    return typeof res.data === "string" ? res.data : String(res.data ?? "");
  },

  // Upload raw text by wrapping it into a Blob/File and reusing uploadFile (multipart)
  uploadTextFile: async (
    username,
    projectName,
    fileName,
    content,
    folder = "",
    contentType = "text/plain"
  ) => {
    const blob = new Blob([content], { type: contentType });
    const file = new File([blob], fileName, { type: contentType });
    return await (async () => {
      const form = new FormData();
      form.append("file", file);
      await API.post(
        `/api/user-storage/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/upload`,
        form,
        {
          params: { folder: folder || undefined },
          headers: { "Content-Type": "multipart/form-data" },
        }
      );
    })();
  },
};
