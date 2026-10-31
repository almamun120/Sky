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

## Step 20: closer to WhatsApp (part 1), dev server address
- **Dev:** ⋮ → "Server address (dev)" changes the server address at any time (also while logged in) and reconnects at once.
  The login screen still has the address field. Remove both when the app goes public. `sky_ws.erl` in `sky_server/src` is the file you sent
  (it prints the OTP on the server console); put the SMS gateway there later.
- **Chat list:** search bar at the top, filter chips (All / Unread / Groups), bottom bar with **Chats** (badge = chats with unread messages)
  and **Calls**. The Calls tab lists all voice and video calls (missed ones in red); tap a row to open that chat.
- **Fixed:** since step 19 "Join group by link" opened together with the "Log out?" dialog (both used number 3).
- Still different from the real WhatsApp: no Updates (Status) and Communities tabs, no dark theme, no emoji/GIF/sticker keyboard, no attach
  sheet with Gallery/Camera/Location/Document/Poll, no camera in the app, no QR code, no Settings screen with sections, no call-back button in
  the Calls tab. These come in the next steps.
- Not compiled or run here.

## Step 21: dark theme, Settings, attachment sheet (looks, part 2)
- **Dark theme** in the colours of the real WhatsApp (dark bars, `#0B141A` chat background, `#005C4B` own bubbles). Settings → Appearance:
  System default / Light / Dark (applies at once). Colours live in `ui/Theme.kt` (`Sky.xxx`); the call screen was already dark.
- **Settings screen** (⋮ → Settings): profile card (opens Profile), Privacy, Blocked contacts (with Unblock), Starred messages, Appearance,
  Notifications (Android's own settings for the app), Storage (size of media on this phone), Server address (dev), Invite a friend, About (version), Log out.
- **Attachment sheet:** the 📎 button opens a bottom sheet with **Gallery**, **Camera** and **Document**. The camera photo is saved in a private cache
  folder, sent encrypted, and the plain copy is deleted the next time the app starts. Android asks for camera permission the first time.
- Not done yet (listed as coming): Location, Contact, Poll, Updates (Status) tab, QR code, emoji/GIF/sticker keyboard.
- Not compiled or run here.

## Step 22: Updates tab (status, 24 hours)
- New bottom tab **Updates** (between Chats and Calls). **My status**: ✏ writes a text update on a coloured background, 📷 posts a photo.
  Below: **Recent updates** of the people you chat with, people with unseen updates first (green ring).
- Viewer: tap right / left for next / previous, moves on by itself after 5 seconds. For your own update it shows how many people looked
  (👁) and a Delete button; a deleted update is removed from the other phones too.
- **Privacy:** the server is not involved. An update is sent as an end-to-end encrypted control message to everyone you have a chat with and
  have not blocked (at the moment you post); a photo is encrypted with its own key, which travels in that message. People you start chatting
  with later do not get older updates. A viewer's phone tells the owner "seen" once.
- Updates disappear after 24 hours (checked at every login; the Updates tab only shows the last 24 hours). Photos are stored encrypted on the phone.
- Local database version 11 (real migration). Version 0.14.0. **No server change** in this step.
- Limits: no "mute updates" or "my contacts except…" lists; no reply to an update; text updates have no font choice; photo updates have no editor;
  the phone must be online to post a photo.
- Not compiled or run here.

## Step A/B in the app: wss and MessagePack
- **Server address** (login screen, Settings → Server address): `wss://your-domain/ws` is the default. A line under the field says "Encrypted connection (wss)" (green),
  "ws:// is not encrypted" (orange, test only) or why the address cannot be used (red); Send code and Save stay disabled until the address is valid.
- **ws:// only in the debug build.** `android:usesCleartextTraffic` is `true` in debug and `false` in release (manifest placeholder `cleartext` in `app/build.gradle.kts`),
  and the app itself also refuses `ws://` in a release build.
- **MessagePack:** on connect the app sends `Sec-WebSocket-Protocol: sky.msgpack, sky.json`. If the server answers `sky.msgpack` it uses binary frames (`net/MsgPack.kt`);
  if it answers `sky.json` or nothing (old server) it uses JSON. Debug switch: Settings → Server address → "JSON text format". The rest of the app is unchanged (still JSONObject).
- Compression (permessage-deflate) is requested by OkHttp automatically; the server decides with `ws_compress`.
- The push channel (`/push`) is still JSON.
- Not compiled or run here (no Android SDK).

## Step 20 (roadmap): friends from contacts, QR code
- **New chat** (green + button): the list of **friends from your contacts who use Sky** (with the names from your address book), a phone number field, "Find friends" and "QR code".
  "Find friends" asks for the contacts permission the first time. The app reads the address book, turns every number into +E.164 form (country from the SIM, else the phone's region, else BD),
  and sends only **short hashes** to the server, which answers with the registered ones. Names stay on the phone. A sync also runs by itself at login, at most every 12 hours.
- An address-book name beats the name the person chose for themselves (they stay marked `local` and are not overwritten by messages).
- **QR code** (New chat → QR code, or Settings → QR code): tab **My code** shows `skychat://user/+880…` as a QR code on a white card (so scanners read it in dark mode too);
  tab **Scan code** opens a scanner (library `zxing-android-embedded`; it asks for the camera itself) and opens the chat with that person. Another camera app that opens
  `skychat://user/+880…` also lands in that chat.
- Local database version 12 (real migration). Version 0.16.0. The server changed: update it first.
- Limits: people who are removed from your address book stay in the list until you log out; the names of contacts that are not on Sky are never stored.
- Not compiled or run here (no Android SDK, no Erlang).

## Step 21 (roadmap): photos, videos, preview, viewer, gallery
- **Preview before sending:** Attach (📎) → **Gallery** now picks up to **10 photos and videos at once**; **Camera** also opens the preview. On the preview screen:
  ⟳ **Rotate** (90° steps), ▣ **Crop** (centre crop to Original / 1:1 / 4:3 / 3:4 / 16:9; free-hand cropping is not there), a **caption for every picture**,
  a strip of thumbnails to switch between pictures or ✕ remove one, then **Send (N)**. Each picture becomes its own message, in order, with its caption under it.
- **Videos:** sent as they are (no re-encoding, so the 25 MB limit applies; bigger files show "too large"). In the chat a video shows its first frame with ▶ and the size;
  the receiver downloads it by tapping (videos are not downloaded by themselves, photos still are), and tapping the picture opens the viewer, whose ▶ plays it in your video app.
  The first-frame picture is kept next to the video (also encrypted on the phone) and removed with it.
- **Full-screen viewer:** tap any photo or video in the chat to open it; swipe left/right through all photos and videos of that chat; **double-tap** a photo to zoom, then pinch and drag
  (double-tap again to reset); the caption is shown at the bottom.
- **Media and documents** (⋮ in a chat): grid of all photos and videos (tap → viewer) and a list of documents (tap → open or download).
- No server change in this step; the database stays at version 12. Version 0.17.0.
- Limits: no video trimming or compression, no drawing/text on pictures, no "view once" (roadmap step 23), no links tab (comes with link previews in step 22).
- Not compiled or run here (no Android SDK).

## Step 22 (roadmap): location, contact card, GIF, sticker, link preview, voice speed
- **Attach sheet** now has Gallery, Camera, Document, **Location**, **Contact**, **GIF**, **Sticker**.
- **Location:** asks for the location permission, finds where you are (GPS or network), shows the coordinates and asks "Send your location?". The receiver sees a card with the
  coordinates; tapping it opens their maps app (`geo:`). No map picture is loaded, so no map company learns where you are. Live location is not included.
- **Contact card:** pick a person from your contacts (no contacts permission needed for this); the receiver sees name and number with **Message** (if the number can be turned into +E.164) and **Save** (opens the add-contact screen).
- **GIF:** pick a `.gif` file from the phone; it is sent untouched and plays in the chat (Android 9 and newer; older phones show the first frame). There is **no online GIF search**
  (it would send what you type to a third-party company and needs a key).
- **Sticker:** pick any picture; it is scaled to at most 512 px and sent as PNG (transparent parts stay transparent). There is no sticker pack store; make your own pictures.
- **Link preview:** when you send a message with a link, **your phone** loads the page (3-4 s limit, first 200 KB) and adds title and description to the message. The receivers never contact the site.
  Switch: Privacy → Link previews. The chat's ⋮ → Media and documents has a new **Links** tab.
- **Voice messages:** while one plays, a chip shows the speed; tap to switch 1× → 1.5× → 2×.
- Local database version 13 (real migration). Version 0.18.0. **No server change** (the new message kinds travel inside the encrypted message).
- Limits: a link preview has no picture; "Message" on a contact card needs a number the phone can turn into +E.164; polls (in the real WhatsApp sheet) are not built.
- Not compiled or run here (no Android SDK).

## Step 23 (roadmap): view once, disappearing messages
- **View once** (one-to-one chats only): on the photo/video preview screen the button **🔥 View once** marks the picture. The receiver sees "🔥 Photo · tap to view once";
  it opens in a window that cannot be screenshotted or screen-recorded (`FLAG_SECURE`, which also hides it in the recent-apps list); when the window is closed the picture is
  **deleted from the phone** and the sender's bubble changes to "Opened". The sender keeps no copy after the upload and cannot forward it; the receiver cannot forward, star or
  find it in the media gallery. A view-once video plays inside the same secure window (a temporary plain copy exists only while it plays and is wiped afterwards).
  If the app is killed while a picture is open, it can be opened once more.
- **Disappearing messages:** chat ⋮ → **Disappearing messages**: Off / 24 hours / 7 days / 90 days. It applies to **new** messages, on both phones (the length travels with each message,
  and the change is announced in the chat: "X turned on disappearing messages: 24 hours"). In a group only admins can change it. A message disappears (with its picture, video or voice file)
  when its time is over, counted from the time it was sent; the app checks every 30 seconds while it runs and at start-up. Starred messages are kept. Messages with a timer show ⏱.
- Local database version 14 (real migration). Version 0.19.0. **No server change.**
- Limits (be honest with your users): a receiver can still photograph the screen with another camera; a message that was not delivered yet can stay on the server until it is fetched; encrypted
  files already uploaded stay on the media server until its own clean-up (step 30); a phone that is switched off keeps expired messages until the app starts again.
- Not compiled or run here (no Android SDK).

## Step 24 (roadmap): group calls, Bluetooth and sound route
- **Sound route** (call screen, the speaker button): choose **Phone, Speaker, Bluetooth headset or wired headset**. At the start of a call a connected headset is chosen by itself, otherwise the
  speaker for video and the earpiece for voice. Android 12 and newer asks for the "Nearby devices" (Bluetooth) permission the first time, so that a Bluetooth headset can be listed.
- **Group calls** (📞 and 🎥 in the title bar of a group chat): everybody in the group gets an invitation (ringtone, or a notification if the app is in the background) and can **Join** or **Decline**;
  people can join later while the call runs. The call screen shows a grid with everybody (video if it is a video call), mute, sound route, camera on/off, switch camera, hang up.
  A line "Group voice call · 12:34" is added to the group chat when it ends (and appears in the Calls tab).
- **How it works:** a mesh: every person is connected directly to every other person (peer-to-peer WebRTC), **at most 4 people in one call** (the 5th person to join cannot connect). There is no media
  server. The signalling (who is in, the connection offers) travels as normal end-to-end encrypted messages between two people (`gc` control messages), so the **server did not change**.
  The ICE candidates are sent together with the offer (no trickle) to keep it to two messages per pair. Of two people the one with the smaller phone number makes the offer.
  Group video is sent small (480x360) because it is sent separately to up to three people.
- A group call and a one-to-one call exclude each other (the second one gets "busy").
- Limits: no more than 4 people; if the phone is switched off or the app is stopped by the system the invitation arrives only when it is online again (messages older than 2 minutes are ignored);
  on bad networks you need a TURN server (`turn_urls` in the server config) for the same reason as one-to-one calls; battery and data use grow with every person; no screen sharing, no adding people
  who are not in the group, no ringing of a person who left the group.
- Not compiled or run here (no Android SDK).

## Step 26 (roadmap): app lock, two-step verification, screenshot block
- **App lock** (Settings → App lock): when the app is opened (or comes back after "immediately / 1 minute / 30 minutes") a lock screen covers it until the person unlocks with the phone's own
  screen lock (PIN, pattern, password or fingerprint; the phone must have one). Calls can still be answered. If the phone's lock is removed later, the app lock switches itself off.
- **Block screenshots** (Settings → Privacy): no screenshots or screen recording of Sky, and Sky's picture in the recent-apps list is blank. It is on automatically while the app lock is on.
- **Two-step verification** (Settings → Two-step verification): a 6-digit PIN. Whoever registers the number on a phone later needs the SMS code **and** the PIN (the app asks for it after the code).
  5 wrong PINs lock the number for 15 minutes. The server stores only a salted hash. If the PIN is forgotten, it falls away after the number was not used for 7 days (and no phone of the account is online).
  Changing or turning off the PIN needs the current PIN. Phones that are already logged in stay logged in.
- Server changed: `set_pin`, `remove_pin`, `verify_pin`, `pin_needed`, `pin_state` (see the server README). New tables `pin` (disk) and `pinfail` (memory).
- Local database unchanged (version 14). Version 0.21.0.
- Limits: the PIN is not asked again while you use the app (WhatsApp sometimes asks); no recovery e-mail; the lock uses the phone's screen lock, not a separate Sky PIN; notification texts stay visible while locked.
- Not compiled or run here.

## Step 27 (roadmap): encrypted chat backup and restore
- **Settings → Chat backup → Back up:** choose a password (at least 8 characters, twice), then choose where to save the file (the system file chooser: phone storage, SD card, Google Drive...).
  The file `sky-backup-YYYYMMDD.skybak` holds the chats (messages, contacts, groups, receipts, reactions, mute/pin/archive settings) and every **photo, video, voice note and file**, decrypted from the phone's own vault
  and locked again with your password: AES-256-GCM in 1 MB pieces, key from PBKDF2-HMAC-SHA256 (250 000 rounds, random salt). A cut-off or changed file is detected.
- **Restore** (same screen): choose the file, type the password. Messages with the same id are replaced by the backup's, everything else on the phone stays. Files are stored again in the phone's vault. Messages that were still unsent when the
  backup was made come back as "failed" (they are not sent by themselves).
- **Not in the backup:** the Signal keys and sessions (so the backup cannot be used to read your future messages if it is stolen; after a restore on a new phone, contacts see a **new safety number**), status updates,
  profile and group pictures (fetched again), the block list (the server has it), settings of this phone.
- **Forgot the password?** Nobody can open the file, not even the developer.
- The backup must be restored with the same app version (same database version); the app says so otherwise. No automatic daily backup (that would need Google's APIs); make one now and then and keep it on Drive or a computer.
- No server change. Database unchanged (14). Version 0.22.0. Not compiled or run here; the password-to-key loop was checked against Python's `hashlib.pbkdf2_hmac`.
