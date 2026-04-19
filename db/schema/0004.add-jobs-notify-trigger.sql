CREATE OR REPLACE FUNCTION notify_jobs() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'SCHEDULED' THEN
    PERFORM pg_notify('jobs_channel', NEW.job_type);
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER notify_jobs_trigger
AFTER INSERT OR UPDATE ON jobs
FOR EACH ROW EXECUTE FUNCTION notify_jobs();
