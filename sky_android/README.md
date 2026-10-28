# Sky Android

Open this folder in Android Studio (Ladybug or newer, JDK 17). Gradle 8.9 is
downloaded automatically from gradle-wrapper.properties.

1. Start the server: `rebar3 shell` in sky_server
2. Emulator: the default server address ws://10.0.2.2:8080/ws works as-is.
   Real phone: use ws://<your PC's LAN IP>:8080/ws
3. Enter a phone number like +8801700000001, tap Send code. In dev mode the
   code is shown on screen. Use a second emulator/phone for another number.

Ticks: clock = not sent, one grey = server has it, two grey = delivered,
two blue = read, red ! = unknown recipient.


## Step 3
- Contact names: shown from what the server reports (set at sign-up).
- New chat checks that the number is registered first.
- Group chats: "New group" in the top bar. Members are added by phone number.
- The local database is rebuilt on upgrade from step 2 (old local messages are cleared).
- Group messages show a single tick only (no per-member receipts yet).

## Step 5: attachments
- 📎 menu: Photo (resized to max ~2400 px, JPEG q80) and File (up to 25 MB).
- 🎤 button: tap to start recording, tap send to send, ✕ to cancel (asks for microphone permission).
- Images and voice notes download automatically; files download on tap. Tap to open/play.
- A message with an attachment shows a clock while it uploads. If the upload fails it is retried
  at the next login.
- Local database is rebuilt again on upgrade (version 3).

## Step 6
- Top bar menu (⋮) -> Log out: revokes the session on the server and removes local chats.
- Expired sessions (30 days unused) send you back to the login screen automatically.
- Internal package id is still com.skychat.app; only the visible name is Sky.

## Step 7: calls
- 📞 / 🎥 in the top bar of a 1:1 chat start a voice / video call (asks for microphone / camera permission).
- Incoming call: full-screen notification when the app is closed (a push wakes it), ringtone when open.
- In-call buttons: mute, speaker, camera on/off, switch camera, hang up.
- Each call leaves a line in the chat ("📞 Voice call · 1:23", "· Missed", "· Declined", "· No answer").
- Calls use STUN by default. For mobile data / strict NAT set up TURN (sky_server/turnserver.conf.example).
- Limits: video freezes while the app is in the background; one call at a time; no Bluetooth routing; no group calls.
- If Gradle cannot resolve io.github.webrtc-sdk:android:125.6422.07, pick the newest version listed on Maven Central.

## Step 8: end-to-end encryption
Uses Signal's open-source **libsignal 0.94.4** (X3DH/PQXDH key agreement with a post-quantum Kyber key, then Double Ratchet).
- Build setup: Gradle pulls libsignal from https://build-artifacts.signal.org/libraries/maven/ (added in settings.gradle.kts).
  Core-library desugaring and `-Xskip-metadata-version-check` are enabled because libsignal is built with a newer Kotlin.
- **Licence: libsignal is AGPL-3.0.** An app that includes it must be distributed with its source under an
  AGPL-compatible licence. Keep this in mind before publishing.
- Each phone creates an identity key at first login and uploads only public keys. Private keys and session state
  are stored in Room, sealed with an Android Keystore key. Auto-backup is disabled so sealed data is never restored elsewhere.
- Messages, file names/types and attachments are encrypted on the phone. Files use a random AES-256-GCM key sent inside the message.
- 🔒 in a 1:1 chat shows the safety number. Compare it with the other person; a change shows a warning line in the chat.
- Plaintext messages from the server are ignored.

Limits: one device per account (reinstalling = new identity, old messages are not restored);
(the local database is now encrypted, see step 12); group members who have not opened the new version cannot receive group messages;
**the crypto layer compiles against libsignal 0.94.4's real
sources but has not been run on a device**: test two phones (including offline delivery and a reinstall) before trusting it.

## Step 9: own push service (no Firebase)
Firebase and google-services.json are gone; the app has no Google dependency for notifications.
- `push/PushService.kt` is a foreground service (shown as a minimal silent "Waiting for messages" notification)
  that keeps one small WebSocket open to the server's `/push` endpoint (same host as the chat address:
  `ws://host:8080/ws` -> `ws://host:8080/push`). When the server sends a wake, the app opens the chat connection,
  downloads and decrypts what is waiting, and shows the notification itself.
- Started when you open the app while logged in, after login, after the phone reboots or Sky is updated
  (`push/BootReceiver.kt`). Stopped on log out. Reconnects by itself (2 s ... 60 s back-off, immediately when the
  network returns).
- On first start the app asks once to be exempt from battery optimisation. Without it some phones stop the service.
  Xiaomi, Huawei, Oppo, Vivo, Samsung "sleeping apps": also allow auto-start / disable "battery saver" for Sky
  (dontkillmyapp.com lists the steps per brand). You can hide the silent notification in the system settings
  (the channel is called "Background connection"); the service keeps running.
- Cost: one idle connection with a small ping every 45 s. Expect a small battery use, similar to a chat app
  that keeps its own connection (Signal without Play Services, Element, Conversations).
- Behaviour on Android 14+: the service uses the "specialUse" foreground type, which may be started at boot.
  If you force-stop the app from system settings Android will not restart it until you open Sky again.
- Not tested on a device. Server side is tested (tests/test_push.py).

## Step 10: groups
- Group chat top bar: ⓘ opens the member list. The creator can add members (by phone number) and remove them;
  everyone can leave. The chat shows "X was added" / "X is no longer in this group" lines.
- Someone who left or was removed keeps the history but sees "You are no longer in this group" instead of the input bar.
- Ticks in groups: one grey = server has it, two grey = delivered to **every** member, two blue = read by **every** member.
- Local database version 5. It is a real migration (adds a `receipts` table), so your messages and Signal keys survive the update.
- Not tested on a device; the server side is tested (tests/test_groups.py). A per-member "seen by" list is not shown yet.

## Step 11: authenticated calls
Call offers and answers are encrypted with the Signal session of the two people. The callee refuses an offer that does not
decrypt (toast "The call could not be verified"), and the caller refuses an answer that does not. This means the server
cannot insert itself into the call. If you have never chatted with the person, the app fetches their keys first
(up to 8 s) before ringing. Old app versions (plain `sdp`) can no longer call new ones: update both phones.
Not tested on a device (needs two phones); the server relay is tested (tests/test_calls_e2e.py).

## Step 12: encrypted local database
The whole Room database (messages, contacts, groups, receipts, Signal state) is now one SQLCipher file (AES-256),
so chat metadata (who, when) is unreadable too, not just the message text.
- `crypto/DbCipher.kt`: the passphrase is 32 random bytes, sealed by the Android Keystore key (`Vault`) and stored in
  its own prefs file `sky_dbkey`. Without that Keystore key `sky.db` cannot be opened.
- **Upgrade from step 11 keeps your data.** On first start the old plaintext `sky.db` is copied into an encrypted file
  (`sqlcipher_export`), the copy is checked (same message count, opens with the key) and only then swapped in.
  A crash half-way leaves the old file, and the migration simply runs again. Schema version stays 5.
- If the Keystore key is ever lost (backup restore, some factory-reset/lock-screen changes) the old database cannot be
  recovered: the app starts clean, removes attachments and shows the login screen again.
- New dependencies: `net.zetetic:sqlcipher-android:4.6.1` and `androidx.sqlite:sqlite-ktx:2.4.0`
  (if Gradle cannot resolve the version, use the newest 4.x on Maven Central). The APK grows by a few MB (native library).
- Not covered: the old plaintext file's blocks may stay in flash until Android reuses them; downloaded attachments in
  `files/media` are still plain files (private to the app, but not encrypted); the login token in SharedPreferences is not sealed.
- Not compiled or run here (no Android SDK / Maven). Test on a phone that already has step 11 data: update the app over
  it, check old chats are still there, then send/receive, reboot, and log out/in.

## Step 13: encrypted attachments and sealed login
- Photos, voice notes and files on the phone (files/media) are now AES-256-GCM encrypted (`crypto/MediaVault.kt`).
  One random file key is sealed by the Android Keystore; files are split in 64 KiB chunks and the
  "last chunk" flag is authenticated, so cut, reordered or altered files are rejected.
- Old plain files from step 12 are encrypted in place on the first start (same file names, so chats keep working).
- Plaintext exists only briefly: thumbnails are decrypted in memory; voice playback and "open with another app"
  use a temporary copy in cache/plain that is deleted after playback / at the next app start.
- Voice recordings and picked files are written to cache/plain first and removed as soon as they are stored.
- The login token and phone number are sealed by the Keystore (`data/Prefs.kt`); old clear-text values are
  converted at the first start. If the Keystore key is lost, the app simply shows the login screen.
- Version 0.5.0. No new dependencies, server unchanged.
- Limits: file names still carry the original extension (the type of a file is visible, not its content);
  a file opened in another app can be copied by that app; plain blocks of step-12 files may remain in flash
  until Android overwrites them (files are overwritten with zeros before deletion, which helps only partly);
  the server address and the "asked battery" flag are still plain preferences.
- **Not compiled or run on a device** (no Android SDK here). The chunk/AES-GCM logic was tested on a JVM
  (round trip, truncation, bit flips, chunk reordering). Test: install over step 12, check old photos/voice notes
  still show and play, send/receive new ones, reboot, log out and in.

## Step 14: typing, online status, search
- "typing…" under the contact's name (groups: "Ana is typing…" / "3 people are typing…") and in the chat list.
- Online / last seen under the name of a 1:1 chat. ⋮ -> Privacy: switch off online status + last seen
  (reciprocal: you then don't see others' either) and the typing indicator.
- Search (magnifier in the chat list): chats by name or number, and messages by text or file name
  (local, in the encrypted database, newest 100). Tapping a message hit opens that chat; it does not scroll to the message yet.
- Version 0.6.0. No new dependencies, database unchanged. Needs the step-14 server.
- Not compiled or run (no Android SDK / Erlang here).

## Step 15: reply, forward, delete, edit, copy
Long-press a message: Reply, Copy, Forward, Edit, Delete.
- **Reply:** the answered text is quoted above your message; tap the quote to jump to the original (if it is still on this phone).
  Works for text messages (replying with a photo/voice note is not in yet; you can reply *to* any message).
- **Forward:** text, photos, voice notes and files (attachments are re-encrypted and uploaded again). Shown as "↪ Forwarded".
- **Delete for me:** removes it from this phone (and shreds the attachment file).
- **Delete for everyone:** your own messages up to 48 h old; both sides show "This message was deleted".
  The request is a hidden end-to-end encrypted control message (kind `ctl`), so the server learns nothing new
  and the server is unchanged. Only the original author can delete or edit a message (checked on the receiving phone).
  If you delete a message that was never sent, it simply disappears.
- **Edit:** your own text messages up to 15 min old; shown as "edited". Same hidden control message.
- Limits: the 48 h / 15 min limits are enforced only by the sender's app (the other side cannot verify them);
  a delete or edit that reaches a phone *before* the original message is ignored; older Sky versions show control
  messages as an empty file, so update every phone; "delete for everyone" cannot remove a copy that someone has
  already saved, forwarded or screenshotted.
- Local database version 6 (a real migration; messages and Signal keys are kept). Version 0.7.0. Server unchanged.
- Not compiled or run (no Android SDK here).

## Step 16: reactions, starred messages, mentions
- **Reactions:** long-press a message and tap 👍 ❤️ 😂 😮 😢 🙏. One reaction per person per message; tap the same
  emoji again (or the pill under the message) to take it back. Sent as a hidden end-to-end encrypted control message
  (like delete/edit), so the server is unchanged. Reactions are only accepted for a message in the same chat.
  Deleting a message removes its reactions.
- **Star:** long-press -> Star. ★ shows next to the time; ⋮ -> Starred messages lists them (tap opens the chat,
  ★ removes it). Stars stay on this phone only.
- **Mentions (groups):** type @ and pick a member; the name is highlighted in the message for everyone, with a
  yellow background if it is you, and your notification starts with "@". The highlight uses the name the sender typed.
- Local database version 7 (real migration: messages and Signal keys are kept). Version 0.8.0. Server unchanged.
- Limits: a reaction that arrives before its message is ignored; the chat list does not show reactions; mentions
  do not yet override "mute" (there is no mute yet); @everyone does not exist; older Sky versions show control
  messages as an empty file, so update every phone.
- Not compiled or run (no Android SDK here).

## Step 17: pin, archive, mute, mark unread, block, report
- **Long-press a chat** in the list: Pin (up to 3, shown first, 📌), Archive (own "Archived" row at the top of the list),
  Mute (8 hours / 1 week / always, 🔇), Mark as unread / read (green dot), Block / Unblock (1:1 only, 🚫).
  Pin, archive, mute and the unread mark stay on this phone.
- **Archive:** archiving unpins. A new incoming message brings an archived chat back to the list, unless it is muted;
  writing in an archived chat also brings it back.
- **Mute:** no notification for that chat. In a muted *group* an @-mention of you still notifies. Unread counts keep
  counting (grey badge).
- **Chat screen ⋮:** Mute, Block/Unblock, Report. A blocked chat shows "You blocked …" with an Unblock button instead
  of the message box, and no call buttons.
- **Block** is enforced by the server (see server README, step 17): the blocked person's messages are acknowledged but never
  delivered, calls ring into the void, typing and online/last seen are hidden both ways. They are not told.
  If a late message from a blocked person still reaches this phone, it is decrypted (so the Signal session stays in step),
  thrown away and removed from the server queue without a receipt.
- **Report:** choose a reason; optionally include the last 5 messages from that person. Messages are end-to-end
  encrypted, so they are sent only if you tick the box (this phone sends them in readable form). Optionally block at the same time.
- Local database version 8 (real migration: messages and Signal keys are kept). Version 0.9.0. The server changed: update it first.
- Limits: blocking does not stop that person's messages in groups; you can still forward a message to someone you
  blocked (it is delivered); groups cannot be reported; messages sent to you while you had someone blocked are lost for good;
  reports have no screen on the server yet, an operator reads them with `sky_db:list_reports()` in a remote shell.
- Not compiled or run (no Android SDK here). The Erlang changes were not compiled or run either (no Erlang here).

## Step 18: profile (name, about, photo), group photo and description
- **Profile screen** (⋮ → Profile): photo (cropped square, 512 px), name, about (up to 139 characters).
  The name is public (it already travels with each message, see `set_name` in the server README). About and photo are
  sealed with a random *profile key* (AES-256-GCM); the server only stores the sealed blob. The photo itself is
  encrypted with its own key, which sits inside the blob.
- **Who can read it:** the profile key is sent once to each person you write to (a hidden encrypted control message, `pkey`).
  Only they can open your blob. When they open the chat, their phone fetches the blob (at most every 10 minutes) and shows
  your photo in the chat list and chat header, and your about text in the contact info (tap the title).
- **Group photo and description:** the group owner sets them in the group info (ⓘ). They go to every member as an
  encrypted control message (`ginfo`) and only the owner's message is accepted. People added later get them
  from the owner automatically. The chat shows "X changed the group description / photo".
- Local database version 9 (real migration, nothing is lost). Version 0.10.0. The server changed: update it first.
- Limits: the profile key is never rotated, so someone who had it before you blocked them keeps it (but the server withholds
  your blob from blocked people, so they only see the version they already had); no photos for people in a group you never
  wrote to one-on-one; no zoom/crop screen (the photo is centre-cropped); the group name cannot be changed yet.
- Not compiled or run (no Android SDK here).

## Step 19: group admins, "only admins can send", invite links, message info
- **Roles:** owner (the creator; if the owner leaves, an admin takes over, else the first member) and admins. Admins can add and remove
  people, change group photo and description, make or dismiss admins, switch "Only admins can send messages", and make invite links.
  An admin cannot remove another admin; only the owner can. The owner cannot be dismissed.
- **Group info (ⓘ or tap the title):** "· owner / · admin" labels, Make admin / Dismiss admin, the switch, and **Invite link**
  (Share, Copy, Reset; after a reset the old link stops working).
- **Only admins can send:** the message box is replaced by a note for ordinary members. The server enforces it. Reactions,
  edits and deletes by members are refused too (the server cannot tell them from messages, because everything is encrypted).
- **Join by link:** ⋮ → Join group by link (paste the link or the code), or open `skychat://join/CODE` (the Android intent filter is in the
  manifest; many chat apps do not make such links tappable, so the share text also contains the plain code).
- **Message info:** long-press your own message in a group → Info: for each member Read, Delivered or Waiting.
- Local database version 10 (real migration). Version 0.11.0. The server changed: update it first.
- Limits: no admin approval for people joining by link; joining does not show old messages; groups made before this step have
  only the owner as admin; not compiled or run (no Android SDK here).

## Automatic builds (GitHub Actions)
File: `.github/workflows/android.yml` (at the top of the repository, next to `sky_android` and `sky_server`).
- Every push to `main`, every pull request: a **debug APK** (Actions → the run → Artifacts → `sky-debug-apk`).
- Push a tag `vX.Y.Z` that equals `versionName` in `app/build.gradle.kts` (for example `git tag v0.11.0 && git push --tags`):
  a **signed release APK and AAB** are built and attached to a GitHub Release.
- One-time setup of the signing key (do this on your computer, never commit the `.jks` file):
  1. `keytool -genkeypair -v -keystore sky.jks -alias sky -keyalg RSA -keysize 2048 -validity 10000`
  2. `base64 -w0 sky.jks` (on macOS: `base64 -i sky.jks`), copy the output.
  3. GitHub → Settings → Secrets and variables → Actions → New repository secret, four of them:
     `SKY_KEYSTORE_BASE64` (the output of step 2), `SKY_KEYSTORE_PASSWORD`, `SKY_KEY_ALIAS` (`sky`), `SKY_KEY_PASSWORD`.
  4. **Keep a safe copy of `sky.jks` and its passwords.** If you lose it you can never update the app for the people who installed it.
- The Gradle wrapper files (`gradlew`) are not in this project; the workflow installs Gradle 8.9 itself.
- Not run (no GitHub here): if a run fails, send the log of the failed step.
