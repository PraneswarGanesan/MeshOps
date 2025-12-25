import json
import os
import time
import shutil
from threading import RLock
from typing import Dict, Any

_DEFAULT: Dict[str, Any] = {
    "w": [0.4, 0.3, 0.2, 0.1],  # weights for [drift, entropy, cost, fatigue]
    "theta": 0.5,               # decision threshold
    "last_retrain_ts": 0.0      # unix seconds
}

class StateStore:
    def __init__(self, path: str):
        self.path = path
        self._lock = RLock()
        d = os.path.dirname(path)
        if d:
            os.makedirs(d, exist_ok=True)
        if not os.path.exists(path):
            self._write(_DEFAULT)

    def _read(self) -> Dict[str, Any]:
        with open(self.path, "r", encoding="utf-8") as f:
            return json.load(f)

    def _write(self, obj: Dict[str, Any]) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=2)

        # retry loop in case Windows Defender locks the file
        for attempt in range(5):
            try:
                # prefer shutil.move on Windows—it handles cross-device locks better
                shutil.move(tmp, self.path)
                break
            except PermissionError:
                time.sleep(0.1)  # wait 100 ms and try again
        else:
            print(f"[WARN] Could not safely replace {self.path}, skipping update")

    def get(self) -> Dict[str, Any]:
        with self._lock:
            return self._read()

    def update(self, **kwargs) -> None:
        with self._lock:
            st = self._read()
            st.update(kwargs)
            self._write(st)

    def mark_retrain_now(self) -> None:
        self.update(last_retrain_ts=time.time())
