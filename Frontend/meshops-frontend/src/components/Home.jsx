// src/pages/Home.jsx
import React, { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

const GOLD   = "#D4AF37";
const GOLD2  = "#B69121";
const SOFT   = "rgba(255,255,255,0.9)"; // increased contrast
const BORDER = "rgba(255,255,255,0.2)";

// const HERO_IMG =
//   "https://plus.unsplash.com/premium_photo-1672088819208-8e2b94b8e27b?q=80&w=687&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D";
const HERO_IMG = "https://images.unsplash.com/photo-1572902788385-d8826402e8ae?q=80&w=1170&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D";
function DotDivider({ label }) {
  return (
    <div className="relative my-14">
      <div className="h-px w-full" style={{ background: BORDER }} />
      <div
        className="absolute -top-2 left-8 w-4 h-4 rounded-full"
        style={{ background: GOLD, boxShadow: `0 0 0 6px rgba(212,175,55,0.25)` }}
      />
      {label ? (
        <div
          className="absolute -top-3 left-14 text-xs tracking-[0.25em] uppercase"
          style={{ color: SOFT }}
        >
          {label}
        </div>
      ) : null}
    </div>
  );
}

function FeatureCard({ title, body, icon }) {
  return (
    <div
      className="rounded-2xl p-6 border hover:-translate-y-1 transition"
      style={{
        borderColor: BORDER,
        background: "linear-gradient(180deg, rgba(255,255,255,0.06), rgba(255,255,255,0.03))",
      }}
    >
      <div className="flex items-center gap-3 mb-3">
        <div
          className="w-10 h-10 rounded-full grid place-items-center"
          style={{ background: "rgba(212,175,55,0.15)", color: GOLD }}
        >
          {icon}
        </div>
        <h4 className="text-lg font-semibold">{title}</h4>
      </div>
      <p className="text-sm leading-6 text-white/85">{body}</p>
    </div>
  );
}

export default function Home() {
  const [isLoggedIn, setIsLoggedIn] = useState(!!localStorage.getItem("token"));
  const navigate = useNavigate();

  useEffect(() => {
    const onStorage = (e) => e.key === "token" && setIsLoggedIn(!!e.newValue);
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);
  useEffect(() => {
    const onFocus = () => setIsLoggedIn(!!localStorage.getItem("token"));
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);

  const handleLogout = () => {
    localStorage.removeItem("token");
    localStorage.removeItem("username");
    setIsLoggedIn(false);
    navigate("/login");
  };

  return (
    <div className="relative w-full bg-[#0B0B12] text-white">
      {/* HERO SECTION */}
      <section className="relative h-screen overflow-hidden">
        <img
          src={HERO_IMG}
          alt="Hero"
          className="absolute inset-0 w-full h-full object-cover"
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(180deg, rgba(11,11,18,0.65), rgba(11,12,18,0.95))",
          }}
        />
        {/* Nav */}
        <header className="relative z-10">
          <div className="max-w-7xl mx-auto px-6 py-6 flex items-center">
            <div className="text-xl tracking-widest font-light">
              <span style={{ color: GOLD }}>M</span>ESHOPS
            </div>
            <nav className="ml-auto hidden md:flex gap-8 text-sm">
              <a href="#about" className="hover:text-white text-white/80">
                ABOUT
              </a>
              <a href="#features" className="hover:text-white text-white/80">
                FEATURES
              </a>
              <a href="#contact" className="hover:text-white text-white/80">
                CONTACT
              </a>
            </nav>
            <div className="ml-6 flex gap-3">
              {isLoggedIn ? (
                <>
                  <Link
                    to="/dashboard"
                    className="px-5 py-2 text-xs tracking-widest border bg-white/10 hover:bg-white hover:text-black transition"
                    style={{ borderColor: BORDER }}
                  >
                    DASHBOARD
                  </Link>
                  <button
                    onClick={handleLogout}
                    className="px-5 py-2 text-xs tracking-widest border hover:bg-white hover:text-black transition"
                    style={{ borderColor: BORDER }}
                  >
                    LOGOUT
                  </button>
                </>
              ) : (
                <Link
                  to="/login"
                  className="px-5 py-2 text-xs tracking-widest border hover:bg-white hover:text-black transition"
                  style={{ borderColor: BORDER }}
                >
                  LOGIN
                </Link>
              )}
            </div>
          </div>
        </header>
        {/* Hero content */}
        <div className="relative z-10 flex flex-col justify-center items-start max-w-7xl mx-auto px-6 h-[80%]">
          <h1 className="text-6xl md:text-7xl lg:text-8xl font-light mb-6 tracking-tight">
            SECURE MLOPS
          </h1>
          <p className="text-lg md:text-xl text-white/90 max-w-xl mb-10">
            A production-ready platform to <span style={{ color: GOLD }}>Test</span>,{" "}
            <span style={{ color: GOLD }}>Deploy</span>, and{" "}
            <span style={{ color: GOLD }}>Optimize</span> ML workflows securely.
          </p>
          <div className="flex gap-4">
            <Link
              to={isLoggedIn ? "/dashboard" : "/login"}
              className="px-8 py-3 border border-white text-sm hover:bg-white hover:text-black transition"
            >
              {isLoggedIn ? "GO TO DASHBOARD" : "GET STARTED"}
            </Link>
            {/* <Link
              to="/signup"
              className="px-8 py-3 border border-white/40 text-sm hover:bg-white/10 transition"
            >
              SIGN UP
            </Link> */}
          </div>
        </div>
        {/* Scroll indicator */}
        <div className="absolute bottom-10 left-1/2 -translate-x-1/2 z-10 flex flex-col items-center">
          <span className="text-white/80 text-xs mb-2 tracking-[0.3em]">SCROLL</span>
          <svg
            className="w-6 h-6 text-white/80 animate-bounce"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1" d="M19 14l-7 7m0 0l-7-7m7 7V3"></path>
          </svg>
        </div>
      </section>

      {/* ABOUT */}
      <section id="about" className="relative z-10 py-20">
        <div className="max-w-7xl mx-auto px-6">
          <DotDivider label="ABOUT US" />
          <div className="grid md:grid-cols-2 gap-12">
            <div>
              <h3 className="text-4xl font-light mb-6">Dependable ML Infrastructure</h3>
              <p className="text-white/90 leading-7 mb-4">
                Meshops is more than a dashboard. It’s a unified environment where your ML
                lifecycle comes alive — from datasets, training, and testing, to deployment
                and monitoring. 
              </p>
              <p className="text-white/80 leading-7">
                We combine <span style={{ color: GOLD }}>security-first design</span>,
                developer-friendly tools, and elegant UI into a single workspace built for
                production readiness.
              </p>
            </div>
            <div
              className="rounded-2xl p-6 border"
              style={{ borderColor: BORDER, background: "rgba(255,255,255,0.04)" }}
            >
              <ul className="space-y-4 text-white/85">
                <li>✔ Versioned S3 storage with artifact lineage</li>
                <li>✔ Live console + logs streaming from runs</li>
                <li>✔ Monaco-powered editor for configs & tests</li>
                <li>✔ Rich charts (confusion matrix, trends)</li>
                <li>✔ Cloud-ready deployment with PSO optimization</li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      {/* FEATURES */}
      <section id="features" className="relative z-10 py-20">
        <div className="max-w-7xl mx-auto px-6">
          <DotDivider label="FEATURES" />
          <div className="grid md:grid-cols-3 gap-6">
            <FeatureCard
              title="Design"
              body="Write drivers, preprocessors, and test cases in a modern Monaco editor with syntax highlighting."
              icon={<span>◎</span>}
            />
            <FeatureCard
              title="Estimate"
              body="Monitor model metrics over time. Track drift, accuracy, and performance across versions."
              icon={<span>▣</span>}
            />
            <FeatureCard
              title="Deploy"
              body="Secure rollout pipelines with PSO-based optimization and one-click promotion to production."
              icon={<span>▲</span>}
            />
          </div>
        </div>
      </section>

      {/* FOOTER */}
      <footer id="contact" className="relative z-10 py-12">
        <div className="max-w-7xl mx-auto px-6">
          <DotDivider />
          <div className="flex flex-col md:flex-row items-start md:items-center gap-6">
            <div className="text-sm text-white/70">
              © {new Date().getFullYear()} Meshops — Built with care.
            </div>
            <div className="md:ml-auto flex gap-4 text-sm">
              <a href="mailto:info@meshops.io" className="hover:underline">info@meshops.io</a>
              <span className="opacity-40">/</span>
              <a href="#" className="hover:underline">Privacy</a>
              <span className="opacity-40">/</span>
              <a href="#" className="hover:underline">Terms</a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}
