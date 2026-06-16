import { FormEvent, useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Save, UserRound } from "lucide-react";
import { toast } from "sonner";
import { parseAxiosMessage } from "../api/client";
import { fetchMyContact, fetchMyProfile, updateMyContact, updateMyProfile } from "../api/profile";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";

type FormState = {
  displayName: string;
  timezone: string;
  mobilePhone: string;
  telegramUsername: string;
  whatsAppE164: string;
};

function mapForm(values?: Partial<FormState> | null): FormState {
  return {
    displayName: values?.displayName ?? "",
    timezone: values?.timezone ?? "Asia/Kolkata",
    mobilePhone: values?.mobilePhone ?? "",
    telegramUsername: values?.telegramUsername ?? "",
    whatsAppE164: values?.whatsAppE164 ?? "",
  };
}

export function ProfilePage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const username = useSessionStore((s) => s.username);
  const email = useSessionStore((s) => s.email);
  const patchDisplayName = useSessionStore((s) => s.patchDisplayName);
  const [form, setForm] = useState<FormState>(mapForm());

  const profileQuery = useQuery({
    queryKey: ["user-profile-page"],
    queryFn: async () => {
      const [profile, contact] = await Promise.all([fetchMyProfile(), fetchMyContact()]);
      return {
        displayName: profile.displayName ?? "",
        timezone: profile.timezone ?? "Asia/Kolkata",
        mobilePhone: contact.mobilePhone ?? "",
        telegramUsername: contact.telegramUsername ?? "",
        whatsAppE164: contact.whatsAppE164 ?? "",
        telegramVerified: contact.telegramVerified,
        whatsAppVerified: contact.whatsAppVerified,
      };
    },
    refetchOnWindowFocus: true,
  });

  useEffect(() => {
    if (!profileQuery.data) return;
    setForm(mapForm(profileQuery.data));
  }, [profileQuery.data]);

  const mutation = useMutation({
    mutationFn: async (values: FormState) => {
      await Promise.all([
        updateMyProfile({
          displayName: values.displayName.trim(),
          timezone: values.timezone.trim(),
        }),
        updateMyContact({
          mobilePhone: values.mobilePhone.trim(),
          telegramUsername: values.telegramUsername.trim(),
          whatsAppE164: values.whatsAppE164.trim(),
        }),
      ]);
      return values;
    },
    onMutate: async (values) => {
      const prevDisplay = useSessionStore.getState().displayName;
      patchDisplayName(values.displayName.trim() || null);
      return { prevDisplay };
    },
    onError: (error, _values, context) => {
      patchDisplayName(context?.prevDisplay ?? null);
      toast.error(parseAxiosMessage(error));
    },
    onSuccess: (values) => {
      patchDisplayName(values.displayName.trim() || null);
      toast.success("Profile updated");
      void profileQuery.refetch();
    },
  });

  const loading = profileQuery.isLoading;
  const saving = mutation.isPending;

  const hasChanges = useMemo(() => {
    if (!profileQuery.data) return false;
    const baseline = mapForm(profileQuery.data);
    return JSON.stringify(baseline) !== JSON.stringify(form);
  }, [form, profileQuery.data]);

  const cardClass = isLight
    ? "rounded-2xl border border-neutral-200 bg-white p-5 shadow-sm sm:p-6"
    : "rounded-2xl border border-neutral-800 bg-neutral-900/50 p-5 sm:p-6";

  function updateField<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!hasChanges || saving) return;
    mutation.mutate(form);
  }

  return (
    <div className="space-y-6 pb-8">
      <div>
        <h1 className={cn("text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>Profile</h1>
        <p className={cn("mt-2 max-w-2xl text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>
          Manage your personal details used for onboarding and operations notifications.
        </p>
      </div>

      <form onSubmit={onSubmit} className={cardClass}>
        {loading ? (
          <div className={cn("text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>Loading profile...</div>
        ) : profileQuery.isError ? (
          <div className={cn("text-sm", isLight ? "text-red-700" : "text-red-300/90")}>
            {parseAxiosMessage(profileQuery.error)}
          </div>
        ) : (
          <div className="space-y-5">
            <div className="grid gap-4 md:grid-cols-2">
              <ReadonlyField label="Username" value={username ?? "-"} isLight={isLight} />
              <ReadonlyField label="Email" value={email ?? "-"} isLight={isLight} />
              <Field
                label="Display name"
                value={form.displayName}
                onChange={(v) => updateField("displayName", v)}
                disabled={saving}
                isLight={isLight}
                placeholder="How your name appears in workspace"
              />
              <Field
                label="Timezone"
                value={form.timezone}
                onChange={(v) => updateField("timezone", v)}
                disabled={saving}
                isLight={isLight}
                placeholder="e.g. Asia/Kolkata"
              />
              <Field
                label="Mobile phone (E.164)"
                value={form.mobilePhone}
                onChange={(v) => updateField("mobilePhone", v)}
                disabled={saving}
                isLight={isLight}
                placeholder="+919876543210"
              />
              <Field
                label="Telegram username"
                value={form.telegramUsername}
                onChange={(v) => updateField("telegramUsername", v)}
                disabled={saving}
                isLight={isLight}
                placeholder="@your_handle"
              />
              <Field
                label="WhatsApp (E.164)"
                value={form.whatsAppE164}
                onChange={(v) => updateField("whatsAppE164", v)}
                disabled={saving}
                isLight={isLight}
                placeholder="+919876543210"
              />
              <ReadonlyField
                label="Verification status"
                value={`Telegram: ${profileQuery.data?.telegramVerified ? "Verified" : "Pending"}  ·  WhatsApp: ${profileQuery.data?.whatsAppVerified ? "Verified" : "Pending"}`}
                isLight={isLight}
              />
            </div>

            <div className={cn("flex flex-wrap items-center justify-between gap-3 border-t pt-4", isLight ? "border-neutral-200" : "border-neutral-800")}>
              <div className={cn("inline-flex items-center gap-2 text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                <UserRound className="h-4 w-4" />
                Username and email are managed by account auth settings and are read-only here.
              </div>
              <button
                type="submit"
                disabled={!hasChanges || saving}
                className={cn(
                  "inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-50",
                  isLight ? "bg-neutral-900 text-white hover:bg-neutral-800" : "bg-white text-neutral-900 hover:bg-neutral-200",
                )}
              >
                <Save className="h-4 w-4" />
                {saving ? "Saving..." : "Save changes"}
              </button>
            </div>
          </div>
        )}
      </form>
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  disabled,
  isLight,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
  isLight: boolean;
  placeholder?: string;
}) {
  return (
    <label className={cn("block text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
      {label}
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        placeholder={placeholder}
        className={cn(
          "mt-1 w-full rounded-lg border px-3 py-2 text-sm outline-none focus:ring-2",
          isLight
            ? "border-neutral-200 bg-white text-neutral-900 focus:border-blue-400 focus:ring-blue-500/25"
            : "border-neutral-700 bg-neutral-950 text-white focus:border-blue-500 focus:ring-blue-500/25",
        )}
      />
    </label>
  );
}

function ReadonlyField({ label, value, isLight }: { label: string; value: string; isLight: boolean }) {
  return (
    <div>
      <div className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>{label}</div>
      <div
        className={cn(
          "mt-1 rounded-lg border px-3 py-2 text-sm",
          isLight ? "border-neutral-200 bg-neutral-50 text-neutral-700" : "border-neutral-700 bg-neutral-900 text-neutral-300",
        )}
      >
        {value}
      </div>
    </div>
  );
}
