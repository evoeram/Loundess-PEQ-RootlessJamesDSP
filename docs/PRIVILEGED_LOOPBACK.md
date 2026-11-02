# Privileged Loopback Capture — What It Solves

The classic rootless route records audio with a **MediaProjection capture loop**: the
app asks for screen/audio capture consent, attaches mute effects to other apps' audio
sessions, and re-renders the captured PCM through the JamesDSP engine. It works, but
every step of that chain has a known failure mode. The **privileged loopback route**
(Shizuku-powered dynamic `AudioPolicy` with a pure `ROUTE_FLAG_LOOP_BACK` mix) removes
the whole chain instead of patching it.

> Short version: matched players are **removed from normal rendering** — the media mix
> flows *only* through JamesDSP. There is nothing to mute, nothing to chase, and nothing
> to ask permission for at the app level.

---

## Problems with the MediaProjection route

| # | Problem | Root cause |
|---|---|---|
| 1 | **Some apps are never processed** (Spotify, Google Chrome, SoundCloud) | They opt out of internal audio capture; without a ReVanced patch their audio simply never enters the capture loop |
| 2 | **Loud burst from feed apps** (Shorts/Reels/TikTok feeds) | Every swipe spawns a new audio player whose session id appears only 8–22 ms before the first sample — no reactive mute attach (callback ~30 ms, effect init 40–100 ms) can cover it |
| 3 | **Capture consent friction** | Projection dialog on start, foreground-service notification, "one-time" consent semantics |
| 4 | **Android 15+ screen-share privacy side effects** | The system treats the session as screen sharing: notifications get hidden, cast status appears |
| 5 | **Fragile session tracking** | Session ids are recycled, `AudioPlaybackConfiguration` is sanitized on Android 12+, dumpsys polling costs 50–100 ms |
| 6 | **Effect attach/detach churn** | Mute effects are constantly added/removed on other apps' audio sessions → occasional glitches and race conditions |

## What the privileged loopback route changes

A Shizuku **UserService** (running with shell/root UID) registers a dynamic
`AudioPolicy` containing one `AudioMix` with `ROUTE_FLAG_LOOP_BACK`. Every player
matching the `AudioMixingRule` (`USAGE_MEDIA` / `GAME` / `UNKNOWN`) is rerouted: its
audio goes into the loopback sink and **never reaches the device unprocessed** — the
only thing playing on the output is the JamesDSP render of that mix.

- **All apps work.** The mix is pulled at the framework level, so per-app capture
  policies (`ALLOW_CAPTURE_BY_NONE`) do not apply. Spotify, Chrome, SoundCloud and
  other capture-blocking apps are processed **without any patch**.
- **The feed-app burst is impossible, not just patched.** The original signal never
  reaches the speaker before processing — there is no window for an unmute blast,
  regardless of how fast a feed spawns new players.
- **No projection dialog, no capture notification, no screen-share side effects.**
  Android 15+ does not hide notifications because the system never sees a capture
  session.
- **No session-id bookkeeping.** No session database, no prediction pool, no anchor
  tracking, no dependence on `AudioPlaybackConfiguration` — the whole mute machinery
  is simply not started in this mode.
- **No per-session effect churn.** Nothing is attached to or detached from other
  apps' sessions.
- **Per-app selection is a filter, not a hack.** Block/allow lists are expressed as
  `AudioMixingRule` UID filters at the policy level.

The DSP engine itself is unchanged — same silence gate, queue control, and effects.
Only the PCM **source** differs (`LoopbackPcmSource` instead of the MediaProjection
`AudioRecord`).

## Requirements and honest limitations

| | |
|---|---|
| **Shizuku** | Server must be running. Wireless-debugging Shizuku stops at reboot; Shizuku started with root survives reboots |
| **Android version** | 13+ (shell attribution requirement for `MODIFY_AUDIO_ROUTING`) |
| **Captured content** | Media/game usage only (`USAGE_MEDIA` / `GAME` / `UNKNOWN`). Calls, alarms, and ringtones are not routed |
| **DRM / offload / tunneled playback** | May bypass the framework mix entirely on some devices |
| **Vendor ROMs** | Dynamic AudioPolicy is an AOSP mechanism; some OEM implementations (e.g. ColorOS) may refuse registration. The app falls back to the MediaProjection route with a user message |
| **Audioserver restart** | Kills the registered policy; the UserService re-registers automatically |

## Setup

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (wireless debugging, or root).
2. In the app, grant Shizuku access when asked.
3. Choose **Standard / Low-latency** processing with the **privileged loopback**
   capture source (offered during onboarding; switchable later in settings).

If Shizuku stops or the policy is refused, the app automatically returns to the
classic MediaProjection loop.

See [PRIVILEGED_LOOPBACK_DESIGN.md](PRIVILEGED_LOOPBACK_DESIGN.md) for the internal
architecture (AIDL transport, UserService layout, integration notes).
