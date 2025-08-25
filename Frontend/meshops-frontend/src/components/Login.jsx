import React, { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { UserAPI } from "../api/axios";

const Login = () => {
  const [form, setForm] = useState({ username: "", password: "" });
  const [error, setError] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const onSubmit = async (e) => {
    e.preventDefault();
    setError("");
    setIsLoading(true);
    try {
      const { data } = await UserAPI.login({
        username: form.username,
        password: form.password,
      });

      // Response fields from your DTO (Token has capital T)
      const token = data.Token || data.token || "";
      const otp = data.otp;
      const username = data.username;

      // Pass to OTP page
      navigate("/otp", { state: { otp, token, username } });
    } catch (err) {
      setError(err.response?.data?.message || "Login failed");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#000000] text-white flex items-center justify-center px-4">
      <div className="w-full max-w-md bg-[#0A0A29] border border-[#2A2A4A] rounded-lg p-8">
        <h2 className="text-2xl font-semibold mb-6">Sign in</h2>

        {error && <div className="bg-red-600 text-white text-sm p-3 rounded mb-4">{error}</div>}

        <form onSubmit={onSubmit} className="space-y-4">
          <input
            name="username" placeholder="Username" value={form.username} onChange={onChange} required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <input
            type="password" name="password" placeholder="Password" value={form.password} onChange={onChange} required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <button
            type="submit" disabled={isLoading}
            className={`w-full py-3 rounded font-semibold transition ${
              isLoading ? "bg-[#2A2A4A] cursor-not-allowed" : "bg-white text-black hover:bg-[#eaeaea]"
            }`}
          >
            {isLoading ? "Signing in..." : "Sign In"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-white/70">
          Don’t have an account?{" "}
          <Link to="/signup" className="text-white underline">Create one</Link>
        </p>
      </div>
    </div>
  );
};

export default Login;
