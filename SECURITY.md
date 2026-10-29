# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Use GitHub's private reporting instead:
**Security** tab → **Report a vulnerability**. (Repository owner: turn this on under Settings → Code security → Private vulnerability reporting.)

Include what you found, how to reproduce it, and which version (app version or commit). You'll get a reply within 7 days. Please give us a reasonable time to fix it before making it public.

## Supported versions

Only the latest release of the app and the current `main` branch of the dashboard and database schema get security fixes.

## How WellShare is protected

WellShare is open source, so its security doesn't depend on hiding code. The database rules in `supabase/schema.sql` decide who can see and change what. The public key inside the app grants nothing by itself, and real secrets are never stored in the code.

Every test, what it proves and when it runs is listed in [docs/SECURITY-TESTING.md](docs/SECURITY-TESTING.md).
