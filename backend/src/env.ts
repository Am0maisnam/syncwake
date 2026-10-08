export interface Env {
  DB: D1Database;
  ALARM_ROOM: DurableObjectNamespace;
  /** Comma-separated OAuth client IDs accepted as the Google ID token audience. */
  GOOGLE_CLIENT_IDS: string;
  INVITE_BASE_URL: string;
  /** Firebase service account JSON; push is disabled when absent. */
  FCM_SERVICE_ACCOUNT?: string;
}
