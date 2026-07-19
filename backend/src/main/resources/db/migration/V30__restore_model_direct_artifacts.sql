-- RAG is optional. Resources that were blocked only because retrieval returned
-- no evidence are valid model-direct outputs under the current publish policy.
-- Keep invalid-citation and weak-grounding blocks untouched.
UPDATE generated_artifact
SET publish_status = 'PUBLISHED'
WHERE UPPER(publish_status) = 'BLOCKED'
  AND UPPER(validation_status) IN ('NO_EVIDENCE', 'RAG_UNUSED')
  AND content_markdown IS NOT NULL
  AND CHAR_LENGTH(TRIM(content_markdown)) > 0;
