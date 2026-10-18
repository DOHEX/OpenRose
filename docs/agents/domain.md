# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Layout

This repo uses a single domain context:

- `GLOSSARY.md` at the repo root defines domain vocabulary.
- `docs/adr/` holds architecture decision records for the whole repo.

## Before exploring

Read `GLOSSARY.md` and the ADRs in `docs/adr/` that touch the area you are about to work in.

If these files or directories are absent, proceed silently. The `domain-modeling` skill creates them lazily when terms or decisions are resolved.

## Use the glossary's vocabulary

Use the glossary's terms when naming domain concepts in issues, proposals, hypotheses, and tests. Follow any guidance about synonyms to avoid.

If a needed concept is missing, reconsider whether it belongs to the domain; record a real vocabulary gap for `domain-modeling`.

## Flag ADR conflicts

When a proposal contradicts an existing ADR, identify the ADR and explain why the decision should be reopened before proceeding.
