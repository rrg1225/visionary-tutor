# Security Policy

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability or leaked credential. Use GitHub's private vulnerability reporting for this repository. If that feature is unavailable, contact the maintainers privately before disclosing details.

Include the affected component, reproduction conditions, potential impact, and a minimal proof of concept that does not access other users' data.

## Secrets and local configuration

- Never commit API keys, passwords, access tokens, private keys, cookies, or production connection strings.
- Copy `.env.example` or `backend/.env.example` to a Git-ignored local file and inject real values through environment variables.
- Treat every browser-side `VITE_*` variable as public information.
- Revoke and rotate a credential immediately if it is committed, even if the commit is later deleted.

## Supported versions

Security fixes are applied to the latest commit on the `main` branch.
