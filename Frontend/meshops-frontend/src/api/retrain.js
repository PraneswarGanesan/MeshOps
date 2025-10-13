const BASE_RETRAIN = "http://127.0.0.1:8000";

export async function startRetrain(payload) {
  const url = `${BASE_RETRAIN}/retrain`;
  console.log("[startRetrain] POST", url, payload);
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json(); // expected: { "id": "pg_cat_dog_im_20251007T170318Z_f90cc1" }
}

export async function getConsole(jobId) {
  const url = `${BASE_RETRAIN}/console/${encodeURIComponent(jobId)}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(await res.text());
  return res.text(); // plain text log
}
