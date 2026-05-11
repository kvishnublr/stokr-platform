import axios, { type AxiosError, type InternalAxiosRequestConfig } from "axios";
import { performLogout } from "../services/auth/logout";
import { useSessionStore } from "../state/session";

const CORRELATION_HEADER = "X-Correlation-Id";

export const api = axios.create({
  baseURL: "/",
});

function parseAxiosMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const d = err.response?.data as {
      message?: string;
      error?: { message?: string; detail?: string };
    } | undefined;
    if (d?.message) return String(d.message);
    const nested = d?.error?.detail ?? d?.error?.message;
    if (nested) return String(nested);
    if (err.response?.status === 401) return "Session expired — please sign in again.";
  }
  if (err instanceof Error) return err.message;
  return "Request failed";
}

export { parseAxiosMessage };

api.interceptors.request.use((config) => {
  const token = localStorage.getItem("accessToken");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  if (!config.headers[CORRELATION_HEADER]) {
    config.headers[CORRELATION_HEADER] = crypto.randomUUID();
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
      !url.includes("/api/auth/verify-email")
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
