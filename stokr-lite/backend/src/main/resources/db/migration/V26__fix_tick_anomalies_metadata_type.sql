-- Change metadata from jsonb to text to match the String field in TickAnomaly.java
-- The entity never populates it with JSON objects, and Hibernate maps String to varchar/text,
-- causing "column is of type jsonb but expression is of type character varying" errors.
ALTER TABLE tick_anomalies ALTER COLUMN metadata TYPE text;
ALTER TABLE tick_anomalies ALTER COLUMN metadata SET DEFAULT '';
