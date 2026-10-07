# Testing SyncWake on a real phone

## 1. Get the app onto the phone

**Option A — no tools needed (download the APK from CI)**
1. On GitHub open **Actions → CI**, pick the latest green run on `main`.
2. Under **Artifacts**, download `syncwake-debug-apk` (a zip) and unzip it to get `app-debug.apk`.
3. Copy it to the phone (USB, Google Drive, email…) and tap it. Allow "Install unknown apps" for
   the app you opened it from when Android asks. Play Protect may warn about an unknown developer;
   choose **Install anyway**.

**Option B — Android Studio (best for debugging)**
1. Install Android Studio, then `git clone https://github.com/Am0maisnam/syncwake` and open the folder.
2. On the phone: Settings → About phone → tap **Build number** 7 times → Developer options →
   enable **USB debugging**. Connect by USB and accept the prompt.
3. Select the phone in the device menu and press **Run**. Logs appear in **Logcat**
   (filter: `package:app.syncwake`).

Command line instead: `./gradlew :app:installDebug`, then `adb logcat | grep -i syncwake`.

## 2. First launch
Allow notifications. Open the readiness card on the home screen and fix anything it lists
(exact alarms, full-screen alerts, alarm volume, battery optimization).

## 3. Test checklist
Set alarms 2–3 minutes ahead. Do each on at least one phone; ideally 3–5 phones from different
brands (Samsung, Xiaomi/Redmi, OnePlus, Pixel) and Android versions.

| # | Test | Expected |
|---|---|---|
| 1 | App open, alarm rings | Full-screen alarm, sound, vibration |
| 2 | Swipe app away from recents, wait | Still rings |
| 3 | Screen off and locked | Screen turns on, alarm shows over the lock screen |
| 4 | Airplane mode | Still rings |
| 5 | Reboot before the alarm, **don't unlock** | Rings on the lock screen |
| 6 | Reboot, unlock, wait | Rings |
| 7 | Phone off through alarm time, turn on within 15 min | Rings late |
| 8 | Same, but turn on after 15+ min | "Missed alarm" notification, history shows Missed |
| 9 | Dismiss → type the code correctly | Brightness rises per character, haptic ticks, alarm stops |
| 10 | Dismiss → wait 20 s | Alarm returns to full volume; a new code next time |
| 11 | Type 3 wrong characters | Fails, alarm resumes |
| 12 | Fail 3 times | 4th attempt adds a simple sum |
| 13 | Press back / home during the challenge | Alarm keeps ringing |
| 14 | Snooze | Silent, rings again after the snooze length |
| 15 | Choose a custom sound, don't touch it for 1 min | Switches to the built-in tone |
| 16 | Complete the challenge, wait 1 min | "Still awake? 👀" notification; tap I'm awake |
| 17 | Ignore "Still awake?" for 2 min | Alarm rings again |
| 18 | Change time zone in Settings before an alarm | Rings at the same local time |
| 19 | Alarm volume at 0 | Readiness card warns |

Battery-saver brands: if 2 or 6 fails on Xiaomi/Samsung/OnePlus, see https://dontkillmyapp.com
for that model and note it — that's a finding we need to handle, not a user error.

## 4. Reporting a problem
Note the phone model, Android version, test number, and what happened. With Option B, copy the
Logcat output around the alarm time. Send these back and the fix goes into the next PR.
