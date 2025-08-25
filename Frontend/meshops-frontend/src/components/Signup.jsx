import React, { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { UserAPI } from "../api/axios";

const Signup = () => {
  const [form, setForm] = useState({
    username: "",
    email: "",
    password: "",
    confirmPassword: "",
    firstName: "",
    lastName: "",
  });
  const [error, setError] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const onSubmit = async (e) => {
    e.preventDefault();
    setError("");
    if (form.password !== form.confirmPassword) {
      setError("Passwords do not match");
      return;
    }
    setIsLoading(true);

    try {
      // Matches your ResgisterRequest DTO
      await UserAPI.register({
        username: form.username,
        email: form.email,
        password: form.password,
        firstName: form.firstName || null,
        lastName: form.lastName || null,
      });
      navigate("/login");
    } catch (err) {
      setError(err.response?.data?.message || "Sign up failed");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#000000] text-white flex items-center justify-center px-4">
      <div className="w-full max-w-md bg-[#0A0A29] border border-[#2A2A4A] rounded-lg p-8">
        <h2 className="text-2xl font-semibold mb-6">Create your account</h2>

        {error && <div className="bg-red-600 text-white text-sm p-3 rounded mb-4">{error}</div>}

        <form onSubmit={onSubmit} className="space-y-4">
          <input
            name="firstName" placeholder="First name" value={form.firstName} onChange={onChange}
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <input
            name="lastName" placeholder="Last name" value={form.lastName} onChange={onChange}
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <input
            name="username" placeholder="Username" value={form.username} onChange={onChange} required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <input
            type="email" name="email" placeholder="Email" value={form.email} onChange={onChange} required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <input
            type="password" name="password" placeholder="Password" value={form.password} onChange={onChange} required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />
          <input
            type="password" name="confirmPassword" placeholder="Confirm password" value={form.confirmPassword} onChange={onChange} required
            className="w-full p-3 bg-[#1A1A3B] border border-[#2A2A4A] rounded placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-white"
          />

          <button
            type="submit" disabled={isLoading}
            className={`w-full py-3 rounded font-semibold transition ${
              isLoading ? "bg-[#2A2A4A] cursor-not-allowed" : "bg-white text-black hover:bg-[#eaeaea]"
            }`}
          >
            {isLoading ? "Creating..." : "Sign Up"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-white/70">
          Already have an account?{" "}
          <Link to="/login" className="text-white underline">Sign in</Link>
        </p>
      </div>
    </div>
  );
};

export default Signup;
