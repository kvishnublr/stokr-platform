import { create } from "zustand";

export type AuthPayload = {
  accessToken: string;
  refreshToken: string;
  userId: string;
  username: string;
  email: string;
  displayName: string | null;
  roles: string[];
  expiresInSeconds: number;
  emailVerified?: boolean;
  telegramVerified?: boolean;
  whatsAppVerified?: boolean;
  onboardingComplete?: boolean;
  liveTradingApproved?: boolean;
};

type SessionState = {
  accessToken: string | null;
  refreshToken: string | null;
  userId: string | null;
  roles: string[];
  username: string | null;
  email: string | null;
  displayName: string | null;
  emailVerified: boolean;
  telegramVerified: boolean;
  whatsAppVerified: boolean;
  onboardingComplete: boolean;
  liveTradingApproved: boolean;
  setSession: (payload: AuthPayload) => void;
  clearSession: () => void;
  hasRole: (role: string) => boolean;
  /** Traders: ROLE_TRADER (preferred) or legacy ROLE_USER; excludes admins-only tooling. */
  hasTraderAccess: () => boolean;
};

function readRoles(): string[] {
  try {
    const raw = localStorage.getItem("roles");
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}

function readBool(key: string): boolean {
  try {
    return localStorage.getItem(key) === "true";
  } catch {
    return false;
  }
}

export const useSessionStore = create<SessionState>((set, get) => ({
  accessToken: localStorage.getItem("accessToken"),
  refreshToken: localStorage.getItem("refreshToken"),
  userId: localStorage.getItem("userId"),
  roles: typeof window !== "undefined" ? readRoles() : [],
  username: localStorage.getItem("username"),
  email: localStorage.getItem("email"),
  displayName: localStorage.getItem("displayName"),
  emailVerified: typeof window !== "undefined" ? readBool("emailVerified") : false,
  telegramVerified: typeof window !== "undefined" ? readBool("telegramVerified") : false,
  whatsAppVerified: typeof window !== "undefined" ? readBool("whatsAppVerified") : false,
  onboardingComplete: typeof window !== "undefined" ? readBool("onboardingComplete") : false,
  liveTradingApproved: typeof window !== "undefined" ? readBool("liveTradingApproved") : false,
  setSession: (payload: AuthPayload) => {
    localStorage.setItem("accessToken", payload.accessToken);
    localStorage.setItem("refreshToken", payload.refreshToken);
    localStorage.setItem("userId", payload.userId);
    localStorage.setItem("username", payload.username);
    localStorage.setItem("email", payload.email);
    localStorage.setItem("displayName", payload.displayName ?? "");
    localStorage.setItem("roles", JSON.stringify(payload.roles));
    const ev = payload.emailVerified ?? false;
    const tv = payload.telegramVerified ?? false;
    const wv = payload.whatsAppVerified ?? false;
    const oc = payload.onboardingComplete ?? false;
    const live = payload.liveTradingApproved ?? false;
    localStorage.setItem("emailVerified", String(ev));
    localStorage.setItem("telegramVerified", String(tv));
    localStorage.setItem("whatsAppVerified", String(wv));
    localStorage.setItem("onboardingComplete", String(oc));
    localStorage.setItem("liveTradingApproved", String(live));
    set({
      accessToken: payload.accessToken,
      refreshToken: payload.refreshToken,
      userId: payload.userId,
      roles: payload.roles,
      username: payload.username,
      email: payload.email,
      displayName: payload.displayName,
      emailVerified: ev,
      telegramVerified: tv,
      whatsAppVerified: wv,
      onboardingComplete: oc,
      liveTradingApproved: live,
    });
  },
  clearSession: () => {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("refreshToken");
    localStorage.removeItem("userId");
    localStorage.removeItem("username");
    localStorage.removeItem("email");
    localStorage.removeItem("displayName");
    localStorage.removeItem("roles");
    localStorage.removeItem("emailVerified");
    localStorage.removeItem("telegramVerified");
    localStorage.removeItem("whatsAppVerified");
    localStorage.removeItem("onboardingComplete");
    localStorage.removeItem("liveTradingApproved");
    set({
      accessToken: null,
      refreshToken: null,
      userId: null,
      roles: [],
      username: null,
      email: null,
      displayName: null,
      emailVerified: false,
      telegramVerified: false,
      whatsAppVerified: false,
      onboardingComplete: false,
      liveTradingApproved: false,
    });
  },
  hasRole: (role: string) => get().roles.includes(role),
  hasTraderAccess: () => {
    const roles = get().roles;
    return roles.some((r) => r === "ROLE_TRADER" || r === "ROLE_USER");
  },
}));
