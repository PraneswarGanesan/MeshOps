// src/pages/Signup.jsx
import React, { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { UserAPI } from "../api/axios";

const GOLD  = "#D4AF37";
const GOLD2 = "#B69121";
const BG    = "#0A0A0F";

// Use any brand image you like here
const SIDE_IMG =
  "https://images.unsplash.com/photo-1451755032734-ada8d61ecf2f?q=80&w=704&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D";

export default function Signup() {
  const [form, setForm] = useState({
    username: "",
    email: "",
    password: "",
    confirmPassword: "",
    firstName: "",
    lastName: "",
  });
  const [error, setError] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const onSubmit = async (e) => {
    e.preventDefault();
    setError("");

    if (form.password !== form.confirmPassword) {
      setError("Passwords do not match");
      return;
    }

    setIsLoading(true);
    try {
      await UserAPI.register({
        username: form.username,
        email: form.email,
        password: form.password,
        firstName: form.firstName || null,
        lastName: form.lastName || null,
      });
      navigate("/login");
    } catch (err) {
      setError(err?.response?.data?.message || "Sign up failed");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen w-full grid lg:grid-cols-2 relative">
      {/* center divider */}
      <div
        className="hidden lg:block absolute left-1/2 top-0 -translate-x-1/2 h-full w-px z-10"
        style={{ background: "linear-gradient(180deg, rgba(212,175,55,0), rgba(212,175,55,0.35), rgba(212,175,55,0))" }}
      />

      {/* LEFT: Form */}
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

          <h2 className="text-2xl font-semibold mb-2 text-white">Create your account</h2>
          <p className="text-white/75 mb-6 text-sm">
            Join Meshops to get secure storage, behavior testing, and clean deploys — all in one place.
          </p>

          {error && (
            <div className="bg-rose-600/90 text-white text-sm p-3 rounded mb-4">
              {error}
            </div>
          )}

          <form onSubmit={onSubmit} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <input
                name="firstName"
                placeholder="First name"
                value={form.firstName}
                onChange={onChange}
                className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none"
                style={{ borderColor: "rgba(255,255,255,0.14)" }}
              />
              <input
                name="lastName"
                placeholder="Last name"
                value={form.lastName}
                onChange={onChange}
                className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none"
                style={{ borderColor: "rgba(255,255,255,0.14)" }}
              />
            </div>

            <input
              name="username"
              placeholder="Username"
              value={form.username}
              onChange={onChange}
              required
              className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none focus:ring-2 focus:ring-[#D4AF37]"
              style={{ borderColor: "rgba(255,255,255,0.14)" }}
            />
            <input
              type="email"
              name="email"
              placeholder="Email"
              value={form.email}
              onChange={onChange}
              required
              className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none focus:ring-2 focus:ring-[#D4AF37]"
              style={{ borderColor: "rgba(255,255,255,0.14)" }}
            />
            <input
              type="password"
              name="password"
              placeholder="Password"
              value={form.password}
              onChange={onChange}
              required
              className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none focus:ring-2 focus:ring-[#D4AF37]"
              style={{ borderColor: "rgba(255,255,255,0.14)" }}
            />
            <input
              type="password"
              name="confirmPassword"
              placeholder="Confirm password"
              value={form.confirmPassword}
              onChange={onChange}
              required
              className="w-full p-3 rounded bg-[#11121A] border text-white placeholder-white/50 outline-none focus:ring-2 focus:ring-[#D4AF37]"
              style={{ borderColor: "rgba(255,255,255,0.14)" }}
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
              {isLoading ? "Creating..." : "Sign Up"}
            </button>
          </form>

          <p className="mt-6 text-sm text-white/75">
            Already have an account?{" "}
            <Link to="/login" className="underline" style={{ color: GOLD }}>
              Sign in
            </Link>
          </p>
        </div>
      </div>

      {/* RIGHT: Image + golden gradient + onboarding bullets */}
      <div className="relative hidden lg:block">
        <img
          src={SIDE_IMG}
          alt="Background"
          className="absolute inset-0 w-full h-full object-cover"
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              `linear-gradient(90deg, rgba(0,0,0,0.88) 0%, rgba(0,0,0,0.72) 18%, rgba(212,175,55,0.18) 52%, rgba(0,0,0,0.55) 78%, rgba(0,0,0,0.75) 100%)`,
          }}
        />
        <div className="absolute bottom-12 left-10 right-10 text-white">
          <h3 className="text-2xl font-light mb-3">Welcome to Meshops</h3>
          <ul className="space-y-2 text-white/95">
            {/* <li>• S3-backed artifact storage</li>
            <li>• Behaviour tests & metrics at a glance</li>
            <li>• Realtime run console & versioned results</li> */}
          </ul>
          <p className="mt-4 text-sm" style={{ color: GOLD }}>
            Start building reliable ML workflows in minutes.
          </p>
        </div>
      </div>
    </div>
  );
}
