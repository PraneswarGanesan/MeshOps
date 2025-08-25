import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import SidePanel from "./SidePanel";
import StorageView from "./StorageView";
import PreProcess from "./PreProcess"; // ⬅️ added
import BehaviourTest from "./BehaviourTest";

const Stub = ({ title }) => (
  <div className="p-8 text-white">
    <h2 className="text-2xl font-semibold">{title}</h2>
    <p className="text-white/70 mt-2">Dashboard area. Add pages here.</p>
  </div>
);

const Dashboard = () => {
  return (
    <div className="min-h-screen bg-[#000000] flex">
      <SidePanel />
      <main className="flex-1 bg-[#0A0A29] border-l border-[#2A2A4A]">
        <Routes>
          <Route path="/" element={<Navigate to="storage" replace />} />
          <Route path="storage" element={<StorageView />} />
          <Route path="pre-process" element={<PreProcess />} /> {/* ⬅️ added */}
          <Route path="behaviour-test" element={<BehaviourTest />} /> {/* ⬅️ added */}
          <Route path="*" element={<Stub title="Not Found" />} />
        </Routes>
      </main>
    </div>
  );
};

export default Dashboard;
