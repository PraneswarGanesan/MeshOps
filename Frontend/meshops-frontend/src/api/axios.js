import axios from "axios";

const api = axios.create({
  baseURL: "http://localhost:8081", // UserService root
  headers: { "Content-Type": "application/json" },
  withCredentials: false,
});

// Simple helpers
export const UserAPI = {
  register: (payload) => api.post("/api/users/auth/register", payload),
  login: (payload) => api.post("/api/users/auth/login", payload),
};

export default api;
