# Backend setup (one time)

The SyncWake API is a Cloudflare Worker (`backend/`) with a D1 database and a Durable Object
for live status. Alarms never depend on it — it only syncs shared alarms and friends' status.

You need three free accounts: **Cloudflare**, **Google Cloud** (sign-in) and **Firebase** (push).
Each step says what to send back or where to put the value. Never paste private keys into chat
or commit them to the repo.

## 1. Cloudflare — database and deploy access

1. Install Node 22, then in `backend/`: `npm ci` and `npx wrangler login`.
2. Create the database: `npx wrangler d1 create syncwake`.
   Copy the printed `database_id` into `backend/wrangler.jsonc` (replacing the zeros) and commit it.
   The id is not secret.
3. For deploys from GitHub: Cloudflare dashboard → My Profile → API Tokens → Create Token →
   template **Edit Cloudflare Workers**, and add the **D1 → Edit** permission.
   In GitHub → repo Settings → Secrets and variables → Actions, add:
   - `CLOUDFLARE_API_TOKEN` — the token
   - `CLOUDFLARE_ACCOUNT_ID` — from the Cloudflare dashboard sidebar
4. Deploy: GitHub → Actions → **Deploy backend** → Run workflow. It runs the tests, applies
   database migrations and deploys. The API URL is printed at the end
   (`https://syncwake-api.<your-subdomain>.workers.dev`).

## 2. Google Sign-In

1. https://console.cloud.google.com → create a project (e.g. "SyncWake").
2. APIs & Services → OAuth consent screen → External → app name, support email → save.
3. Credentials → Create credentials → OAuth client ID:
   - **Web application** (name "SyncWake server"). Copy its **Client ID**. This is what the
     server checks and what the Android app sends as `serverClientId`.
   - **Android**: package `app.syncwake`, plus the SHA-1 of your signing key
     (debug: `./gradlew :app:signingReport`). No value to copy; it just authorises the app.
4. Put the Web client ID in `backend/wrangler.jsonc` → `vars.GOOGLE_CLIENT_IDS` and commit it
   (client IDs are not secret). Send it to me too: the Android app needs it in Phase 3b.

## 3. Firebase Cloud Messaging (push)

1. https://console.firebase.google.com → Add project → choose the same Google Cloud project.
2. Add an Android app with package `app.syncwake`. Download `google-services.json`.
   It goes in `app/` for Phase 3b. It contains API keys that are restricted to your app, but keep
   the repository private if you commit it, or add it as a CI secret instead (I'll set that up).
3. Project settings → Service accounts → Generate new private key → a JSON file downloads.
   **This one is secret.** Store it only as a Worker secret:
   `cd backend && npx wrangler secret put FCM_SERVICE_ACCOUNT` and paste the whole JSON.
   Without it the API still works; it just doesn't send push notifications.

## 4. Check it

`curl https://syncwake-api.<your-subdomain>.workers.dev/health` → `{"ok":true}`.

## Local development

`cd backend && npm ci && npm run migrate:local && npm run dev` serves the API on
http://localhost:8787. `npm test` runs the test suite in the Workers runtime; no account needed.
