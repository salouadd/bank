DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        GROUP BY lower(trim(email))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot normalize user emails: duplicate addresses exist after trimming and lowercasing';
    END IF;
END;
$$;

UPDATE users
SET email = lower(trim(email))
WHERE email <> lower(trim(email));

CREATE UNIQUE INDEX ux_users_email_normalized ON users (lower(email));
