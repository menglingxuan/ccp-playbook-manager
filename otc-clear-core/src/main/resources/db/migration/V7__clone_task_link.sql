-- A cloned batch belongs to the task of the batch it was cloned from, so it is listed next to the
-- original batch in the task's batch list instead of living only in the batch console.
UPDATE import_batch c
SET task_id = (SELECT p.task_id FROM import_batch p WHERE p.id = c.parent_batch_id)
WHERE c.kind = 'CLONE'
  AND c.task_id IS NULL
  AND EXISTS (SELECT 1 FROM import_batch p WHERE p.id = c.parent_batch_id AND p.task_id IS NOT NULL);

-- The task counter now reflects every active batch of the task, clones included.
UPDATE import_task t
SET batch_count = (SELECT COUNT(*) FROM import_batch b WHERE b.task_id = t.id AND b.status <> 'DELETED');
