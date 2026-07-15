-- Run this once in the Supabase SQL editor (Project > SQL Editor > New query)
-- before installing the migrated app. Non-destructive: only adds columns/indexes.

-- 1. Link each cloud row back to the local Bill.id it came from, and
--    carry over the fields the app already tracks locally so nothing is lost.
alter table documents
  add column if not exists client_id text unique,
  add column if not exists ref_number text default '',
  add column if not exists notes text default '',
  add column if not exists ocr_text text default '';

-- 2. Tombstones need to say WHICH document (by client_id) was deleted.
--    The uuid `id` column stays as-is (default gen_random_uuid()); client_id
--    is the join key the app uses to apply/propagate deletions.
alter table documents_tombstones
  add column if not exists client_id text;

create index if not exists idx_documents_client_id on documents (client_id);
create index if not exists idx_tombstones_client_id on documents_tombstones (client_id);
