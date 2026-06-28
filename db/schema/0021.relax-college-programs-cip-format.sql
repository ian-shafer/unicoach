-- Relax college_programs.cip_code from exactly-six-digits to the real CIP
-- grammar: 2, 4, or 6 digits. 0015's `^[0-9]{6}$` assumed every Scorecard
-- CIPCODE was a 6-digit detail code, but the Field-of-Study file encodes codes
-- at 2-digit series (e.g. '09'), 4-digit family ('0901'), and 6-digit detail
-- ('090101') granularity. The 6-only CHECK rejected the 2- and 4-digit rows en
-- masse, so college_programs loaded almost empty against real data.
--
-- The new pattern admits exactly those three widths and nothing else: 1-, 3-,
-- and 5-digit values cannot be valid CIP codes and would most often be a
-- leading zero stripped by an upstream integer coercion ('0901' -> '901') --
-- exactly the corruption the cip_code column is TEXT (not INT) to defend
-- against. The constraint name is reused so any assertion keyed on it keeps
-- matching, and DDL is unaffected by row triggers.

ALTER TABLE college_programs
  DROP CONSTRAINT college_programs_cip_code_format_check;
ALTER TABLE college_programs
  ADD CONSTRAINT college_programs_cip_code_format_check
    CHECK (cip_code ~ '^([0-9]{2}){1,3}$');
