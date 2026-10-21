-- Pause paper auto-entry for Jade Lizard and Ratio Butterfly (21-Oct-2026 loss analysis:
-- lopsided downside / extra short leg). Re-enable from the Strategy Control Center.
DO $$
BEGIN
  IF to_regclass('public.option_arb_auto_exec_settings') IS NOT NULL THEN
    UPDATE option_arb_auto_exec_settings SET setting_value = 'false', updated_at = now()
     WHERE setting_key IN ('STRAT_JADE_LIZARD_paperEnabled', 'STRAT_RATIO_BUTTERFLY_paperEnabled');
    INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value, description, updated_at)
    SELECT k, 'false', 'Paused 2026-10-21 after loss analysis', now()
      FROM (VALUES ('STRAT_JADE_LIZARD_paperEnabled'), ('STRAT_RATIO_BUTTERFLY_paperEnabled')) v(k)
     WHERE NOT EXISTS (SELECT 1 FROM option_arb_auto_exec_settings s WHERE s.setting_key = v.k);
  END IF;
END $$;
