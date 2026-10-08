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
   - **Android**: package `app.syncwake`, SHA-1
     `9B:D6:80:55:D9:2B:D7:58:98:68:09:2D:83:DC:F8:1F:C1:5C:F3:F7`
     (the committed debug key `app/debug.keystore`, used by every debug build including CI).
     No value to copy; it just authorises the app. A Play release will need a second Android
     client with the release key's SHA-1.
4. Put the Web client ID in `backend/wrangler.jsonc` → `vars.GOOGLE_CLIENT_IDS` and commit it
   (client IDs are not secret). Send it to me too: the Android app needs it in Phase 3b.

## 3. Firebase Cloud Messaging (push)

1. https://console.firebase.google.com → Add project → choose the same Google Cloud project.
2. Add an Android app with package `app.syncwake` (SHA-1 as above). You don't need to add
   `google-services.json` to the repo; the four values below are read from it instead.
3. Project settings → Service accounts → Generate new private key → a JSON file downloads.
   **This one is secret.** Store it only as a Worker secret:
   `cd backend && npx wrangler secret put FCM_SERVICE_ACCOUNT` and paste the whole JSON.
   Without it the API still works; it just doesn't send push notifications.

## 4. Connect the app

GitHub → repo Settings → Secrets and variables → Actions → **Variables** tab → New repository
variable, for each of these (none are secret):

| Variable | Where to find it |
|---|---|
| `SYNCWAKE_API_URL` | Your Worker URL, e.g. `https://syncwake-api.<subdomain>.workers.dev` |
| `SYNCWAKE_GOOGLE_SERVER_CLIENT_ID` | The **Web** OAuth client ID from step 2 |
| `SYNCWAKE_FIREBASE_APP_ID` | `google-services.json` → `client[0].client_info.mobilesdk_app_id` |
| `SYNCWAKE_FIREBASE_API_KEY` | `google-services.json` → `client[0].api_key[0].current_key` |
| `SYNCWAKE_FIREBASE_PROJECT_ID` | `google-services.json` → `project_info.project_id` |
| `SYNCWAKE_FIREBASE_SENDER_ID` | `google-services.json` → `project_info.project_number` |

The next build of `main` (or re-run the latest CI run) produces an APK with Friends switched on.
For local builds, put the same values in `~/.gradle/gradle.properties` as
`syncwake.apiUrl`, `syncwake.googleServerClientId`, `syncwake.firebaseAppId`,
`syncwake.firebaseApiKey`, `syncwake.firebaseProjectId`, `syncwake.firebaseSenderId`.

## 5. Check it

`curl https://syncwake-api.<your-subdomain>.workers.dev/health` → `{"ok":true}`.

## Local development

`cd backend && npm ci && npm run migrate:local && npm run dev` serves the API on
http://localhost:8787. `npm test` runs the test suite in the Workers runtime; no account needed.
