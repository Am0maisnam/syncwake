import { createApp } from "./app";
import { googleVerifier } from "./auth";
import { fcmSender, noPush, type PushSender } from "./push";

export { AlarmRoom } from "./room";

// Reused across requests in the same isolate so the FCM access token is cached.
let pushSender: { key: string; sender: PushSender } | null = null;

const app = createApp({
  verifyGoogle: (env) => googleVerifier(env.GOOGLE_CLIENT_IDS.split(",").map((s) => s.trim()).filter(Boolean)),
  push: (env) => {
    if (!env.FCM_SERVICE_ACCOUNT) return noPush;
    if (pushSender?.key !== env.FCM_SERVICE_ACCOUNT) {
      pushSender = { key: env.FCM_SERVICE_ACCOUNT, sender: fcmSender(env.FCM_SERVICE_ACCOUNT) };
    }
    return pushSender.sender;
  },
  now: () => Date.now(),
});

export default app;
