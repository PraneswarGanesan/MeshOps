import { useState, useEffect } from "react";
import { startRetrain, getConsole } from "../api/retrain";

export default function Retrain() {
  const [files, setFiles] = useState([]);
  const [consoleOutput, setConsoleOutput] = useState("");
  const [jobId, setJobId] = useState(null);
  const [isRunning, setIsRunning] = useState(false);

  const username = "pg";
  const projectName = "cat_dog_im";
  const version = "v2";

  const saveBase = `pg/${projectName}/artifacts/versions/${version}`;
  const requirementsPath = `pg/${projectName}/pre-processed/requirment.txt`;

  const startRetrainClick = async () => {
    const payload = {
      username,
      projectName,
      files,
      saveBase,
      requirementsPath,
    };
    try {
      const out = await startRetrain(payload);
      setJobId(out.id);
      setIsRunning(true);
      setConsoleOutput("Retrain job started...\n");
    } catch (err) {
      alert("Retrain failed: " + err.message);
    }
  };

  // Poll console every 3 seconds
  useEffect(() => {
    if (!isRunning || !jobId) return;
    const timer = setInterval(async () => {
      try {
        const text = await getConsole(jobId);
        setConsoleOutput(text);
      } catch (err) {
        setConsoleOutput((prev) => prev + "\n[Error fetching console]");
      }
    }, 3000);
    return () => clearInterval(timer);
  }, [isRunning, jobId]);

  return (
    <div className="p-6">
      <h2 className="text-xl font-semibold mb-4">Retrain Module</h2>

      <div className="border rounded-lg p-4 mb-4 bg-gray-900 text-gray-100">
        <h3 className="font-medium mb-2">Select Files</h3>
        <textarea
          value={files.join("\n")}
          onChange={(e) => setFiles(e.target.value.split("\n").map(f => f.trim()).filter(Boolean))}
          className="w-full h-40 bg-black text-green-400 font-mono text-sm p-2 rounded"
          placeholder={`Enter S3 paths line by line...\nExample:\npg/cat_dog_im/pre-processed/train.py`}
        />
      </div>

      <button
        onClick={startRetrainClick}
        className="bg-yellow-500 text-black px-4 py-2 rounded hover:bg-yellow-400"
        disabled={isRunning}
      >
        Start Retrain
      </button>

      <div className="mt-6 border rounded-lg bg-black text-green-400 p-4 font-mono text-sm h-80 overflow-auto">
        {consoleOutput || "Console output will appear here..."}
      </div>
    </div>
  );
}
