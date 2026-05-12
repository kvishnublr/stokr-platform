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

export type ProfileFlagsPatch = {
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
  /** Merge verification/onboarding flags from server (e.g. onboarding summary) without replacing tokens. */
  patchProfileFlags: (flags: ProfileFlagsPatch) => void;
  patchDisplayName: (displayName: string | null) => void;
  clearSession: () => void;
  hasRole: (role: string) => boolean;
  /** Traders: ROLE_TRADER (preferred) or legacy ROLE_USER; excludes admins-only tooling. */
  hasTraderAccess: () => boolean;
  /**
   * Kill-switch / emergency ops console — platform staff only.
   * Traders never see this even if ROLE_ADMIN is mistakenly combined with ROLE_TRADER.
   */
  canAccessKillSwitchOperations: () => boolean;
};

function readRoles(): string[] {
  try {
    const raw = localStorage.getItem("roles");
    return raw ? normalizeRoles(JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}

/** Backend puts authorities in the JWT `scope` claim (space-separated). Prefer that on cold load so gates match the token if `roles` in localStorage is stale. */
function parseRolesFromAccessToken(accessToken: string): string[] | null {
  try {
    const parts = accessToken.split(".");
    if (parts.length < 2) return null;
    let b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const rem = b64.length % 4;
    if (rem) b64 += "=".repeat(4 - rem);
    const payload = JSON.parse(atob(b64)) as { scope?: unknown };
    if (typeof payload.scope !== "string" || !payload.scope.trim()) return null;
    return payload.scope.trim().split(/\s+/).filter(Boolean);
  } catch {
    return null;
  }
}

function readRolesReconciledWithJwt(accessToken: string | null): string[] {
  if (!accessToken) return readRoles();
  const fromToken = parseRolesFromAccessToken(accessToken);
  if (!fromToken?.length) return readRoles();
  const merged = normalizeRoles(fromToken);
  const prev = readRoles();
  const same =
    merged.length === prev.length && merged.every((r) => prev.includes(r)) && prev.every((r) => merged.includes(r));
  if (!same) {
    try {
      localStorage.setItem("roles", JSON.stringify(merged));
    } catch {
      /* ignore */
    }
  }
  return merged;
}

function normalizeRoleName(role: string): string {
  const trimmed = role.trim().toUpperCase();
  return trimmed.startsWith("ROLE_") ? trimmed : `ROLE_${trimmed}`;
}

function normalizeRoles(roles: string[]): string[] {
  return Array.from(new Set(roles.filter((r) => typeof r === "string" && r.trim().length > 0).map(normalizeRoleName)));
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
  roles:
    typeof window !== "undefined"
      ? readRolesReconciledWithJwt(localStorage.getItem("accessToken"))
      : [],
  username: localStorage.getItem("username"),
  email: localStorage.getItem("email"),
  displayName: localStorage.getItem("displayName"),
  emailVerified: typeof window !== "undefined" ? readBool("emailVerified") : false,
  telegramVerified: typeof window !== "undefined" ? readBool("telegramVerified") : false,
  whatsAppVerified: typeof window !== "undefined" ? readBool("whatsAppVerified") : false,
  onboardingComplete: typeof window !== "undefined" ? readBool("onboardingComplete") : false,
  liveTradingApproved: typeof window !== "undefined" ? readBool("liveTradingApproved") : false,
  setSession: (payload: AuthPayload) => {
    const normalizedRoles = normalizeRoles(payload.roles ?? []);
    localStorage.setItem("accessToken", payload.accessToken);
    localStorage.setItem("refreshToken", payload.refreshToken);
    localStorage.setItem("userId", payload.userId);
    localStorage.setItem("username", payload.username);
    localStorage.setItem("email", payload.email);
    localStorage.setItem("displayName", payload.displayName ?? "");
    localStorage.setItem("roles", JSON.stringify(normalizedRoles));
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
      roles: normalizedRoles,
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
  patchProfileFlags: (flags) => {
    const s = get();
    const emailVerified = flags.emailVerified !== undefined ? flags.emailVerified : s.emailVerified;
    const telegramVerified = flags.telegramVerified !== undefined ? flags.telegramVerified : s.telegramVerified;
    const whatsAppVerified = flags.whatsAppVerified !== undefined ? flags.whatsAppVerified : s.whatsAppVerified;
    const onboardingComplete = flags.onboardingComplete !== undefined ? flags.onboardingComplete : s.onboardingComplete;
    const liveTradingApproved =
      flags.liveTradingApproved !== undefined ? flags.liveTradingApproved : s.liveTradingApproved;
    try {
      localStorage.setItem("emailVerified", String(emailVerified));
      localStorage.setItem("telegramVerified", String(telegramVerified));
      localStorage.setItem("whatsAppVerified", String(whatsAppVerified));
      localStorage.setItem("onboardingComplete", String(onboardingComplete));
      localStorage.setItem("liveTradingApproved", String(liveTradingApproved));
    } catch {
      /* ignore */
    }
    set({
      emailVerified,
      telegramVerified,
      whatsAppVerified,
      onboardingComplete,
      liveTradingApproved,
    });
  },
  patchDisplayName: (displayName) => {
    try {
      localStorage.setItem("displayName", displayName ?? "");
    } catch {
      /* ignore */
    }
    set({ displayName });
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
  hasRole: (role: string) => get().roles.includes(normalizeRoleName(role)),
  hasTraderAccess: () => {
    const roles = get().roles;
    return roles.some((r) => r === "ROLE_TRADER" || r === "ROLE_USER");
  },
  canAccessKillSwitchOperations: () => {
    const roles = get().roles;
    return roles.includes("ROLE_ADMIN") && !roles.includes("ROLE_TRADER");
  },
}));
