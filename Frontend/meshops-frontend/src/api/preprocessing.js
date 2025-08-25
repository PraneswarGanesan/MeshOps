import axios from "axios";

const API = axios.create({
  baseURL: "http://localhost:8081", // change to 8081 if this controller runs there
  headers: { "Content-Type": "application/json" },
});

API.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

export const PreprocessAPI = {
  trigger: (username, projectName, folder = "", files = null) =>
    API.post(
      `/api/preprocessing/${encodeURIComponent(username)}/projects/${encodeURIComponent(projectName)}/trigger`,
      files, // may be null or []
      { params: { folder: folder || undefined } }
    ),
};
