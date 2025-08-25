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
     { path: "/dashboard/behaviour-test", label: "bheaviour testS" },
    // { path: "/dashboard/get-analytics", label: "PRE PROCESS" },
    // { path: "/dashboard/select-device", label: "BEHAVIOUR TEST" },
  ];

  return (
    <aside className={`${collapsed ? "w-20" : "w-64"} transition-all duration-300 bg-[#000000] border-r border-[#2A2A4A] min-h-screen`}>
      <div className="p-6 border-b border-[#2A2A4A] flex items-center justify-between">
        {!collapsed && <div className="text-white tracking-wider">MESHOPS</div>}
        <button className="text-white/60 hover:text-white" onClick={() => setCollapsed(!collapsed)}>
          {collapsed ? "»" : "«"}
        </button>
      </div>

      <nav className="p-4 flex flex-col gap-2">
        {items.map((it) => (
          <Link
            key={it.path}
            to={it.path}
            className={`px-4 py-3 rounded border ${
              isActive(it.path)
                ? "border-white/30 bg-[#1A1A3B] text-white"
                : "border-transparent text-white/70 hover:border-white/20 hover:bg-[#1A1A3B]/50"
            } ${collapsed ? "text-center" : ""}`}
          >
            {collapsed ? it.label[0] : it.label}
          </Link>
        ))}
      </nav>
    </aside>
  );
};

export default SidePanel;
