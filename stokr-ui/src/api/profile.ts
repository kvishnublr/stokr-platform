import { api } from "./client";

export type UserProfileDto = {
  displayName: string | null;
  timezone: string | null;
};

export type ContactDto = {
  mobilePhone: string | null;
  telegramUsername: string | null;
  whatsAppE164: string | null;
  telegramVerified: boolean;
  whatsAppVerified: boolean;
};

export type ProfileFormPayload = {
  displayName: string;
  timezone: string;
  mobilePhone: string;
  telegramUsername: string;
  whatsAppE164: string;
};

type ApiEnvelope<T> = { data?: T };

export async function fetchMyProfile(): Promise<UserProfileDto> {
  const res = await api.get<ApiEnvelope<UserProfileDto>>("/api/users/me/profile");
  const data = res.data?.data;
  return {
    displayName: data?.displayName ?? null,
    timezone: data?.timezone ?? null,
  };
}

export async function fetchMyContact(): Promise<ContactDto> {
  const res = await api.get<ApiEnvelope<ContactDto>>("/api/trader/contact");
  const data = res.data?.data;
  return {
    mobilePhone: data?.mobilePhone ?? null,
    telegramUsername: data?.telegramUsername ?? null,
    whatsAppE164: data?.whatsAppE164 ?? null,
    telegramVerified: Boolean(data?.telegramVerified),
    whatsAppVerified: Boolean(data?.whatsAppVerified),
  };
}

export async function updateMyProfile(payload: { displayName: string; timezone: string }) {
  await api.put("/api/users/me/profile", payload);
}

export async function updateMyContact(payload: { mobilePhone: string; telegramUsername: string; whatsAppE164: string }) {
  await api.patch("/api/trader/contact", payload);
}
