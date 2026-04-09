CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    -- Physical timestamp is ALWAYS updated
    NEW.row_updated_at = NOW();

    -- Logical timestamp is updated to the transaction time unless bypassed by role
    -- Controlled via a custom session configuration parameter
    IF current_setting('unicoach.bypass_logical_timestamp', true) IS DISTINCT FROM 'true' THEN
        NEW.updated_at = NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION enforce_versioning()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.version IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'Initial version must be 1' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.version IS DISTINCT FROM (OLD.version + 1) THEN
            RAISE EXCEPTION 'Optimistic Concurrency Control conflict: row was modified by another transaction or version was not provided.'
            USING ERRCODE = '40001'; -- serialization_failure
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_physical_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Physical deletions are strictly blocked. Use soft deletes by setting deleted_at.' USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'The id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'The created_at field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.row_created_at IS DISTINCT FROM OLD.row_created_at THEN
        RAISE EXCEPTION 'The row_created_at field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
