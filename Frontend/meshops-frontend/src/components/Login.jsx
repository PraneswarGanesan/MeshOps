// src/pages/Login.jsx
import React, { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { UserAPI } from "../api/axios";

const GOLD  = "#D4AF37";
const GOLD2 = "#B69121";
const BG    = "#0A0A0F";

// Use your own brand image if you have it
const SIDE_IMG =
  "https://plus.unsplash.com/premium_photo-1667546902538-cab6804a05f8?q=80&w=687&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D";

export default function Login() {
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
      const token = data.Token || data.token || "";
      const otp = data.otp;
      const username = data.username;
      navigate("/otp", { state: { otp, token, username } });
    } catch (err) {
      setError(err?.response?.data?.message || "Login failed");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen w-full grid lg:grid-cols-2 relative">
      {/* center divider (gold, subtle) */}
      <div
        className="hidden lg:block absolute left-1/2 top-0 -translate-x-1/2 h-full w-px z-10"
        style={{ background: "linear-gradient(180deg, rgba(212,175,55,0), rgba(212,175,55,0.35), rgba(212,175,55,0))" }}
      />

      {/* LEFT: Login Form */}
      <div className="relative flex items-center justify-center px-8 sm:px-12" style={{ background: BG }}>
        {/* ambient gold glow */}
        <div
          className="pointer-events-none absolute -top-24 -left-24 w-[520px] h-[520px] rounded-full blur-3xl opacity-30"
          style={{ background: "radial-gradient(closest-side, rgba(212,175,55,0.22), transparent 70%)" }}
        />
        <div className="w-full max-w-md relative z-[1]">
          {/* brand */}
          <div className="mb-10">
            <div className="text-2xl font-semibold tracking-wide">
              <span style={{ color: GOLD }}>M</span>
              <span className="text-white">eshops</span>
            </div>
            <div className="mt-1 h-[2px] w-14" style={{ background: GOLD }} />
          </div>

          <h2 className="text-2xl font-semibold mb-2 text-white">Welcome Back</h2>
          <p className="text-white/75 mb-6 text-sm">
            Please enter your credentials to continue.
          </p>

          {error && (
            <div className="bg-rose-600/90 text-white text-sm p-3 rounded mb-4">
              {error}
            </div>
          )}

          <form onSubmit={onSubmit} className="space-y-4">
            <input
              name="username"
              placeholder="Username"
              value={form.username}
              onChange={onChange}
              required
              className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none focus:ring-2"
              style={{ borderColor: "rgba(255,255,255,0.14)", boxShadow: "inset 0 0 0 1px rgba(255,255,255,0.04)" }}
            />
            <input
              type="password"
              name="password"
              placeholder="Password"
              value={form.password}
              onChange={onChange}
              required
              className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none focus:ring-2 focus:ring-[#D4AF37]"
              style={{ borderColor: "rgba(255,255,255,0.14)", boxShadow: "inset 0 0 0 1px rgba(255,255,255,0.04)" }}
            />
            <button
              type="submit"
              disabled={isLoading}
              className="w-full py-3 rounded font-medium transition text-black"
              style={{
                background: isLoading
                  ? "linear-gradient(180deg, rgba(255,255,255,0.7), rgba(255,255,255,0.7))"
                  : `linear-gradient(180deg, ${GOLD}, ${GOLD2})`,
                boxShadow: "0 12px 28px rgba(212,175,55,0.25)",
                opacity: isLoading ? 0.7 : 1,
              }}
            >
              {isLoading ? "Signing in..." : "Sign In"}
            </button>
          </form>

          <p className="mt-6 text-sm text-white/75">
            Don’t have an account?{" "}
            <Link to="/signup" className="underline" style={{ color: GOLD }}>
              Sign up for free
            </Link>
          </p>
        </div>
      </div>

      {/* RIGHT: Image + gold/black gradient hue */}
      <div className="relative hidden lg:block">
        {/* image */}
        <img
          src={SIDE_IMG}
          alt="Background"
          className="absolute inset-0 w-full h-full object-cover"
        />
        {/* gold + black overlay (adds warm tone, keeps contrast) */}
        <div
          className="absolute inset-0"
          style={{
            background:
              `linear-gradient(90deg, rgba(0,0,0,0.88) 0%, rgba(0,0,0,0.72) 18%, rgba(212,175,55,0.18) 52%, rgba(0,0,0,0.55) 78%, rgba(0,0,0,0.75) 100%)`,
          }}
        />
        {/* subtle vignette */}
        <div
          className="absolute inset-0 pointer-events-none"
          style={{
            background:
              "radial-gradient(1200px 400px at 70% 90%, rgba(212,175,55,0.18), transparent 60%)",
            mixBlendMode: "soft-light",
          }}
        />

        {/* quote */}
        <div className="absolute bottom-10 left-10 right-10 text-white">
          <p className="italic text-lg text-white/95">
            “We work 10x faster than competitors, while they’re stuck in debt.”
          </p>
          <span className="block mt-2 text-sm" style={{ color: GOLD }}>
            — Meshops
          </span>
        </div>
      </div>
    </div>
  );
}
