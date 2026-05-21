ALTER TABLE user_permissions
    ADD COLUMN IF NOT EXISTS permission_manage_acquisition BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE user_permissions up
SET up.permission_manage_acquisition = TRUE
WHERE up.permission_admin = TRUE;
