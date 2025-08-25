import React, { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

const HERO_IMG =
  "https://images.unsplash.com/photo-1637270866876-86f0b1acfe7c?q=80&w=1170&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D";

function Home() {
  const [isLoggedIn, setIsLoggedIn] = useState(!!localStorage.getItem("token"));
  const navigate = useNavigate();

  // keep session in sync if token changes elsewhere (e.g., another tab or after OTP)
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key === "token") {
        setIsLoggedIn(!!e.newValue);
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  // also re-check on focus (in case token changed while user was away)
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
    <div className="relative min-h-screen w-full overflow-hidden bg-[#0A0A29] text-white font-sans">
      {/* Background image */}
      <img
        src={HERO_IMG}
        alt="Hero"
        className="absolute inset-0 w-full h-full object-cover grayscale"
        loading="eager"
      />
      {/* Dark overlay */}
      <div className="absolute inset-0 bg-gradient-to-b from-[#0A0A29]/80 via-[#0A0A29]/60 to-[#0A0A29]/90" />

      {/* Top-right nav */}
      <div className="absolute top-0 left-0 right-0 p-6 md:p-10 flex justify-end items-center z-20">
        {isLoggedIn ? (
          <div className="flex gap-3">
            <Link
              to="/dashboard"
              className="text-white border border-white/30 px-5 py-2 rounded-none hover:bg-white hover:text-[#0A0A29] transition-all duration-300 text-sm tracking-wider"
            >
              DASHBOARD
            </Link>
            <button
              onClick={handleLogout}
              className="text-white border border-white/30 px-5 py-2 rounded-none hover:bg-white hover:text-[#0A0A29] transition-all duration-300 text-sm tracking-wider"
            >
              LOGOUT
            </button>
          </div>
        ) : (
          <Link
            to="/login"
            className="text-white border border-white/30 px-5 py-2 rounded-none hover:bg-white hover:text-[#0A0A29] transition-all duration-300 text-sm tracking-wider"
          >
            LOGIN
          </Link>
        )}
      </div>

      {/* Main hero content */}
      <div className="relative z-10 w-full max-w-6xl mx-auto px-6 min-h-screen flex items-center">
        <div className="relative w-full">
          {/* Oversized background title */}
          <div className="absolute -top-24 md:-top-32 left-0 w-full opacity-10 pointer-events-none">
            <h1 className="text-9xl md:text-[12rem] lg:text-[15rem] font-extralight text-white tracking-tight leading-none">
              MESHOPS
            </h1>
          </div>

          <div className="relative ml-4 md:ml-12 pt-12">
            {/* Vertical divider */}
            <div className="absolute top-0 left-0 w-px h-full bg-white/20" />

            {/* Title */}
            <div className="mb-12 md:mb-16 relative">
              <div className="absolute -left-4 top-1/2 -translate-y-1/2 w-8 h-px bg-white" />
              <h2 className="text-6xl md:text-7xl font-light text-white tracking-wide ml-8">
                MESHOPS
              </h2>
            </div>

            {/* Subtitle + CTA */}
            <div className="ml-8 md:ml-16 max-w-lg">
              <p className="text-lg md:text-xl font-extralight text-white/90 tracking-wide mb-12">
                SECURE MLOPS • TEST • DEPLOY • OPTIMIZE
              </p>

              {isLoggedIn ? (
                <Link
                  to="/dashboard"
                  className="group relative inline-block overflow-hidden border border-white px-8 py-4"
                >
                  <span className="absolute inset-0 bg-white translate-x-full transition-transform duration-300 group-hover:translate-x-0" />
                  <span className="relative text-white text-sm tracking-widest font-light transition-colors duration-300 group-hover:text-black">
                    GO TO DASHBOARD
                  </span>
                </Link>
              ) : (
                <div className="flex gap-3">
                  <Link
                    to="/login"
                    className="group relative inline-block overflow-hidden border border-white px-8 py-4"
                  >
                    <span className="absolute inset-0 bg-white translate-x-full transition-transform duration-300 group-hover:translate-x-0" />
                    <span className="relative text-white text-sm tracking-widest font-light transition-colors duration-300 group-hover:text-black">
                      GET STARTED
                    </span>
                  </Link>
                  <Link
                    to="/signup"
                    className="border border-white/40 px-8 py-4 text-sm tracking-widest font-light hover:bg-white/10 transition-all"
                  >
                    SIGN UP
                  </Link>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Scroll indicator */}
      <div className="absolute bottom-8 left-1/2 -translate-x-1/2 flex flex-col items-center animate-pulse">
        <span className="text-white/70 text-xs tracking-widest mb-2">SCROLL</span>
        <svg className="w-6 h-6 text-white/70" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1" d="M19 14l-7 7m0 0l-7-7m7 7V3"></path>
        </svg>
      </div>
    </div>
  );
}

export default Home;
