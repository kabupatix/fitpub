-- Create with default true to update all existing activities to 'published'
alter table activities
    add column published BOOLEAN NOT NULL DEFAULT TRUE;

-- Change the default
alter table activities
    alter column published SET DEFAULT FALSE;