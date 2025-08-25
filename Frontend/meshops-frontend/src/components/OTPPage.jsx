import React, { useState } from "react";
import { useLocation, useNavigate, Link } from "react-router-dom";

const OTPPage = () => {
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
    <div className="min-h-screen bg-[#000000] text-white flex items-center justify-center px-4">
      <div className="w-full max-w-md bg-[#0A0A29] border border-[#2A2A4A] rounded-lg p-8">
        <h2 className="text-2xl font-semibold mb-6">Verify OTP</h2>
        <p className="text-white/70 text-sm mb-4">
          We’ve sent a 6-digit code to <span className="text-white">{username || "your account"}</span>
        </p>

        {error && <div className="bg-red-600 text-white text-sm p-3 rounded mb-4">{error}</div>}

        <form onSubmit={onSubmit} className="space-y-4">
          <input
            value={otpInput}
            onChange={(e) => setOtpInput(e.target.value)}
            maxLength={6} placeholder="Enter 6-digit OTP" required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <button
            type="submit" disabled={isLoading}
            className={`w-full py-3 rounded font-semibold transition ${
              isLoading ? "bg-[#2A2A4A] cursor-not-allowed" : "bg-white text-black hover:bg-[#eaeaea]"
            }`}
          >
            {isLoading ? "Verifying..." : "Verify"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-white/70">
          <Link to="/login" className="underline">Back to Login</Link>
        </p>
      </div>
    </div>
  );
};

export default OTPPage;
