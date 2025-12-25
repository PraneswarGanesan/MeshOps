import React, { useState } from "react";
import { Link, useLocation } from "react-router-dom";

const SidePanel = () => {
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const isActive = (p) => pathname === p;

  const items = [
    { path: "/", label: "HOME" },
    { path: "/dashboard/storage", label: "STORAGE" },
    { path: "/dashboard/pre-process", label: "PRE PROCESS" },
    { path: "/dashboard/behaviour-test", label: "BEHAVIOUR TEST" },
    { path: "/dashboard/unit-test", label: "UNIT TEST" },
    { path: "/dashboard/retrain", label: "RETRAIN MODEL" },
  ];

  return (
    <aside className={`${collapsed ? "w-20" : "w-72"} relative transition-all duration-500 ease-out bg-gradient-to-b from-slate-950 via-slate-900 to-slate-950 border-r border-amber-500/10 min-h-screen shadow-2xl shadow-amber-500/5`}>
      {/* Animated gradient overlay */}
      <div className="absolute inset-0 bg-gradient-to-br from-amber-500/5 via-transparent to-blue-500/5 pointer-events-none" />
      
      {/* Accent glow lines */}
      <div className="absolute left-0 top-0 w-px h-full bg-gradient-to-b from-transparent via-amber-400/30 to-transparent" />
      <div className="absolute right-0 top-0 w-px h-full bg-gradient-to-b from-transparent via-blue-400/20 to-transparent" />
      
      <div className="relative z-10">
        <div className="p-6 border-b border-gradient-to-r from-amber-500/20 via-blue-500/20 to-transparent backdrop-blur-sm flex items-center justify-between group">
          {!collapsed && (
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-amber-500 via-amber-600 to-blue-600 shadow-lg shadow-amber-500/30 flex items-center justify-center">
                <span className="text-white font-bold text-lg">M</span>
              </div>
              <div>
                <div className="text-transparent bg-clip-text bg-gradient-to-r from-amber-300 via-amber-200 to-blue-300 font-bold tracking-wider text-lg">
                  MESHOPS
                </div>
                <div className="text-amber-500/50 text-xs font-medium">Neural Platform</div>
              </div>
            </div>
          )}
          <button 
            className="w-9 h-9 rounded-lg bg-gradient-to-br from-amber-500/10 to-blue-500/10 border border-amber-500/20 hover:border-amber-400/40 text-amber-300/60 hover:text-amber-200 transition-all duration-300 hover:shadow-lg hover:shadow-amber-500/20 hover:scale-105 active:scale-95 flex items-center justify-center" 
            onClick={() => setCollapsed(!collapsed)}
          >
            <span className="text-sm font-bold">{collapsed ? "»" : "«"}</span>
          </button>
        </div>

        <nav className="p-4 flex flex-col gap-2">
          {items.map((it, index) => (
            <Link
              key={it.path}
              to={it.path}
              className={`group relative px-4 py-3.5 rounded-xl transition-all duration-300 overflow-hidden ${
                isActive(it.path)
                  ? "bg-gradient-to-r from-amber-500/20 via-amber-600/20 to-blue-500/20 border border-amber-400/30 text-white shadow-lg shadow-amber-500/10"
                  : "border border-transparent text-white/60 hover:text-white hover:bg-gradient-to-r hover:from-amber-500/5 hover:to-blue-500/5 hover:border-amber-500/20"
              } ${collapsed ? "text-center justify-center flex" : ""}`}
              style={{
                animationDelay: `${index * 50}ms`
              }}
            >
              {/* Active indicator line */}
              {isActive(it.path) && (
                <div className="absolute left-0 top-1/2 -translate-y-1/2 w-1 h-8 bg-gradient-to-b from-amber-400 via-amber-500 to-blue-500 rounded-r-full shadow-lg shadow-amber-500/50" />
              )}
              
              {/* Hover glow effect */}
              <div className={`absolute inset-0 rounded-xl opacity-0 group-hover:opacity-100 transition-opacity duration-500 bg-gradient-to-r from-amber-500/5 to-blue-500/5 blur-sm`} />
              
              {/* Text content */}
              <span className={`relative z-10 font-semibold tracking-wide text-sm transition-all duration-300 ${
                isActive(it.path) 
                  ? "text-transparent bg-clip-text bg-gradient-to-r from-amber-300 to-blue-300" 
                  : "group-hover:text-amber-200/90"
              }`}>
                {collapsed ? it.label[0] : it.label}
              </span>
              
              {/* Active item shimmer effect */}
              {isActive(it.path) && (
                <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/5 to-transparent" 
                     style={{
                       backgroundSize: '200% 100%',
                       animation: 'shimmer 3s infinite'
                     }}
                />
              )}
            </Link>
          ))}
        </nav>
      </div>
      
      <style jsx>{`
        @keyframes shimmer {
          0% { background-position: -200% 0; }
          100% { background-position: 200% 0; }
        }
      `}</style>
    </aside>
  );
};

export default SidePanel;