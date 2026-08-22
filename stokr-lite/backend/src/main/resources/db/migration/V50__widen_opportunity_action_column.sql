-- Condor's action string ("BUY_CONDOR CE (24000/24200/24400/24600)", 4 strikes) runs to
-- ~39 chars, well past the action column's VARCHAR(30) -- every condor scan was failing
-- to persist with "value too long for type character varying(30)". Butterfly/Vertical/Box
-- fit within 30 today but are one strike-digit away from the same failure, so widen with
-- real headroom rather than the exact minimum.
ALTER TABLE option_arb_opportunities ALTER COLUMN action TYPE VARCHAR(80);
