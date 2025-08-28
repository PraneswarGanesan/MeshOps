// src/components/OTPPage.jsx
import React, { useState } from "react";
import { useLocation, useNavigate, Link } from "react-router-dom";

const GOLD = "#D4AF37";
const GOLD_SOFT = "#B69121";
const BORDER = "rgba(255,255,255,0.18)";

// Background image (swap to your brand image if you want)
const BG_IMG =
  "https://images.unsplash.com/photo-1637270866876-86f0b1acfe7c?q=80&w=1600&auto=format&fit=crop";

export default function OTPPage() {
  const [otpInput, setOtpInput] = useState("");
  const [error, setError] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();
  const { state } = useLocation();

  const serverOtp = state?.otp;
  const token = state?.token;
  const username = state?.username;

  const onSubmit = (e) => {
    e.preventDefault();
    setError("");
    setIsLoading(true);
    try {
      if (!serverOtp || !token || !username) {
        setError("Invalid session. Please login again.");
        return;
      }
      if (String(otpInput).trim() === String(serverOtp).trim()) {
        localStorage.setItem("token", token);
        localStorage.setItem("username", username);
        navigate("/dashboard");
      } else {
        setError("Invalid OTP");
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen w-full overflow-hidden">
      {/* Background image only (hero style) */}
      <div className="absolute inset-0">
        <img
          src={BG_IMG}
          alt=""
          className="w-full h-full object-cover"
          loading="eager"
        />
        {/* subtle golden/black overlay for contrast */}
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(120deg, rgba(0,0,0,0.82) 0%, rgba(10,10,41,0.78) 45%, rgba(212,175,55,0.12) 70%, rgba(0,0,0,0.70) 100%)",
          }}
        />
      </div>

      {/* centered card */}
      <div className="relative z-10 min-h-screen flex items-center justify-center px-4">
        <div
          className="w-full max-w-md rounded-2xl p-8"
          style={{
            color: "white",
            background:
              "linear-gradient(180deg, rgba(255,255,255,0.10), rgba(255,255,255,0.05))",
            backdropFilter: "blur(12px)",
            WebkitBackdropFilter: "blur(12px)",
            border: `1px solid ${BORDER}`,
            boxShadow:
              "0 20px 60px rgba(0,0,0,0.35), inset 0 0 0 1px rgba(255,255,255,0.06)",
          }}
        >
          {/* brand */}
          <div className="mb-6">
            <div className="text-xl font-semibold tracking-wide">
              <span style={{ color: GOLD }}>M</span>eshops
            </div>
            <div
              className="mt-2 h-[2px] w-12"
              style={{ background: GOLD }}
            />
          </div>

          <h2 className="text-2xl font-semibold mb-2">Verify OTP</h2>
          <p className="text-white/85 text-sm mb-5">
            Enter the 6-digit code sent to{" "}
            <span className="text-white font-medium">
              {username || "your account"}
            </span>
            .
          </p>

          {error && (
            <div className="bg-rose-600/90 text-white text-sm p-3 rounded mb-4">
              {error}
            </div>
          )}

          <form onSubmit={onSubmit} className="space-y-4">
            <input
              value={otpInput}
              onChange={(e) =>
                setOtpInput(e.target.value.replace(/\D/g, ""))
              }
              maxLength={6}
              inputMode="numeric"
              autoFocus
              className="w-full p-3 rounded outline-none bg-black/35 border text-white placeholder-white/60 tracking-[0.6em] text-center font-mono text-lg"
              style={{
                borderColor: "rgba(255,255,255,0.22)",
                boxShadow: "inset 0 0 0 1px rgba(255,255,255,0.05)",
              }}
            />

            <button
              type="submit"
              disabled={isLoading}
              className="w-full py-3 rounded font-medium transition text-black"
              style={{
                background: isLoading
                  ? "linear-gradient(180deg, rgba(255,255,255,0.78), rgba(255,255,255,0.78))"
                  : `linear-gradient(180deg, ${GOLD}, ${GOLD_SOFT})`,
                boxShadow: "0 12px 28px rgba(212,175,55,0.25)",
                opacity: isLoading ? 0.85 : 1,
              }}
            >
              {isLoading ? "Verifying…" : "Verify"}
            </button>
          </form>

          <div className="mt-6 text-center text-sm">
            <Link
              to="/login"
              className="underline text-white/90 hover:text-white"
            >
              Back to Login
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
