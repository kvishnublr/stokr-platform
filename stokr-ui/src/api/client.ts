import axios, { type AxiosError, type InternalAxiosRequestConfig } from "axios";
import { randomUuid } from "../lib/utils";
import { performLogout } from "../services/auth/logout";
import { useSessionStore } from "../state/session";

const CORRELATION_HEADER = "X-Correlation-Id";

export const api = axios.create({
  baseURL: "/",
});

function mapBrokerUserMessage(raw: string): string | null {
  const m = String(raw ?? "").toLowerCase();
  if (!m) return null;
  if (m.includes("the user is not enabled for the app")) {
    return "This Zerodha account is not enabled for the connected Kite app. Please reconnect Zerodha and complete app authorization for this user.";
  }
  if (m.includes("tokenexception") || m.includes("incorrect `api_key` or `access_token`")) {
    return "Zerodha session expired/invalid. Please reconnect Zerodha to re-authenticate.";
  }
  if (m.includes("invalid from date") || m.includes("invalid to date")) {
    return "Invalid backfill date window for broker API. Use a weekday IST market session (09:15-15:30) and keep end time in the past.";
  }
  return null;
}

function parseAxiosMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (!err.response) {
      const code = err.code;
      const msg = (err.message || "").toLowerCase();
      const isLocalDev =
        typeof window !== "undefined" &&
        (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1");
      if (
        code === "ERR_NETWORK" ||
        code === "ECONNREFUSED" ||
        msg.includes("network error") ||
        msg.includes("connection refused") ||
        msg.includes("failed to fetch")
      ) {
        if (isLocalDev) {
          return "Cannot reach the API. Start Spring Boot (stokr-bootstrap) on port 8080 so Vite can proxy /api from this dev server - or set STOKR_BACKEND_ORIGIN (or STOKR_API_PROXY_TARGET) when starting Vite if the API uses another URL.";
        }
        return "Cannot reach the API. Check that the UI proxy/nginx routes /api to the backend and that the API process is running.";
      }
    }
    const d = err.response?.data as {
      message?: string;
      error?: { message?: string; detail?: string; code?: string };
    } | undefined;
    const nested = d?.error?.detail ?? d?.error?.message;
    if (nested) {
      const mapped = mapBrokerUserMessage(String(nested));
      if (mapped) return mapped;
      return String(nested);
    }
    if (d?.message) {
      const mapped = mapBrokerUserMessage(String(d.message));
      if (mapped) return mapped;
      return String(d.message);
    }
    const rawBody = typeof err.response?.data === "string" ? err.response.data : "";
    if (rawBody) {
      const mapped = mapBrokerUserMessage(rawBody);
      if (mapped) return mapped;
    }
    if (err.response?.status === 401) return "Session expired - please sign in again.";
    if (err.response?.status === 403) {
      const rawBody403 = typeof err.response?.data === "string" ? err.response.data : "";
      if (rawBody403.toLowerCase().includes("cors")) {
        return "Login blocked by server CORS policy. Try again in a minute or contact support.";
      }
    }
    if (err.response?.status === 503) {
      return "API is starting — portfolio and ops data will sync automatically in a few seconds.";
    }
    const st = err.response?.status;
    if (st && st >= 500) {
      const isLocalDev =
        typeof window !== "undefined" &&
        (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1");
      if (isLocalDev) {
        return `Server error (${st}). Is the API running on port 8080? Check backend logs for the real cause.`;
      }
      return `Server error (${st}). Check backend logs and reverse-proxy routing to the API.`;
    }
  }
  if (err instanceof Error) return err.message;
  return "Request failed";
}

export { parseAxiosMessage };

const AUTH_PATHS_WITHOUT_BEARER = [
  "/api/auth/login",
  "/api/auth/register",
  "/api/auth/refresh",
  "/api/auth/forgot-password",
  "/api/auth/reset-password",
  "/api/auth/verify-email",
];

api.interceptors.request.use((config) => {
  const url = config.url ?? "";
  const skipBearer = AUTH_PATHS_WITHOUT_BEARER.some((p) => url.includes(p));
  const token = localStorage.getItem("accessToken");
  if (token && !skipBearer) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  if (!config.headers[CORRELATION_HEADER]) {
    config.headers[CORRELATION_HEADER] = randomUuid();
  }
  return config;
});

api.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const original = error.config as InternalAxiosRequestConfig & { _retry?: boolean };
    const url = original?.url ?? "";
    if (
      error.response?.status === 401 &&
      !original._retry &&
      !url.includes("/api/auth/login") &&
      !url.includes("/api/auth/register") &&
      !url.includes("/api/auth/refresh") &&
      !url.includes("/api/auth/verify-email") &&
      !url.includes("/api/auth/forgot-password") &&
      !url.includes("/api/auth/reset-password")
    ) {
      original._retry = true;
      const rt = localStorage.getItem("refreshToken");
      if (!rt) {
        await performLogout();
        return Promise.reject(error);
      }
      try {
        const res = await axios.post("/api/auth/refresh", { refreshToken: rt }, { baseURL: "/" });
        const payload = res.data?.data;
        if (payload?.accessToken && payload?.refreshToken) {
          useSessionStore.getState().setSession({
            accessToken: payload.accessToken,
            refreshToken: payload.refreshToken,
            userId: String(payload.userId ?? ""),
            username: payload.username,
            email: payload.email,
            displayName: payload.displayName ?? null,
            roles: payload.roles ?? [],
            expiresInSeconds: payload.expiresInSeconds ?? 0,
            emailVerified: Boolean(payload.emailVerified),
            telegramVerified: Boolean(payload.telegramVerified),
            whatsAppVerified: Boolean(
              payload.whatsAppVerified ?? (payload as { whatsappVerified?: boolean }).whatsappVerified,
            ),
            onboardingComplete: Boolean(payload.onboardingComplete),
            liveTradingApproved: Boolean(payload.liveTradingApproved),
          });
          original.headers.Authorization = `Bearer ${payload.accessToken}`;
          return api(original);
        }
      } catch {
        await performLogout();
      }
    }
    return Promise.reject(error);
  },
);
