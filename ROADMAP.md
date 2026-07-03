# Vibe TV + MyKiddieTv — Product Roadmap

Owner: product. Scope: two Android/Fire TV IPTV apps sharing a codebase — **Vibe TV** (full player) and **MyKiddieTv** (kid-safe variant). Goal: raise usability and build defensible differentiation ahead of a possible marketplace launch (Amazon Appstore / Fire TV first).

**North-star differentiators** (lean into these): multi-language *content profiles*, in-app *trailers + rich metadata*, free *multi-view*, and the *kid-safe* app (a near-empty niche).

Effort key: **S** = ~1 build cycle · **M** = a few · **L** = large / may need a backend. App tags: **[V]** Vibe · **[K]** MyKiddieTv · **[Both]**.

---

## Phase 0 — Polish & quick wins (make it *feel* premium)
Fast, visible upgrades that leverage things we already have.
- **TMDb posters + ratings for Movies** [V] **M** — fill missing art, show IMDb/TMDb rating (portal ratings are always 0), backdrops on the info sheet. Biggest visual jump for least work; reuses `Tmdb.kt`.
- **"Recently added" + "Trending" rails on home** [V] **S–M** — uses portal `added` + TMDb trending.
- **Language-first onboarding** [Both] **M** — first run: pick languages → auto-create matching content profiles + rails. Makes our profile edge obvious in 30s.
- **Sleep timer + "Still watching?" idle prompt** [Both] **S**.
- **Playback settings** (buffer size, HW/SW decode, default aspect, autoplay-next toggle) [Both] **S–M**.
- **Unified search** across Live + VOD (+ EPG later) in one result list [V] **M**.
- **Channel zapping niceties**: last-channel toggle, on-screen number entry [V] **S**.

## Phase 1 — The Guide (EPG) — *the headline feature*
The portal's native EPG is mostly "no guide"; a real guide is what people pay competitors for.
- **External XMLTV EPG** [V] **L** — user pastes an XMLTV URL (or auto-map a public source), matched to channels by `xmltv_id`/name. Caches locally.
- **Full TV-guide grid + now/next** [V] **M** (depends on XMLTV).
- **Program reminders / notifications** [V] **M** (depends on EPG).
- **Catch-up reliability** [V] **M** — proper archive playback driven by real EPG timestamps (today's catch-up is best-effort).

## Phase 2 — Reach (support more providers) — *market expander*
Today we're Stalker-only; this multiplies the addressable audience.
- **Xtream Codes account type** [Both] **L** — most subscriptions offer it; new account client + login flow.
- **M3U playlist support** [Both] **L** — the broadest format.
- **Multi-account management** [Both] **M** — store/switch multiple providers (pairs naturally with profiles).

## Phase 3 — Reliability & trust ("it just works")
IPTV's #1 complaint is dead/buffering streams.
- **Stream health + auto-retry on drop** [Both] **M**.
- **Source failover** — try a channel's alternate `cmds[]` automatically [V] **M**.
- **"Report not working" → quietly hide dead entries** [Both] **S–M** (the radio-relay lesson: HTTP-200 ≠ playable).
- **Connection diagnostics screen** (portal reachable / token valid / stream test) [Both] **S**.

## Phase 4 — Personalization & sync
- **"For You" rails** from watch history (genre/language heuristics — no backend) [V] **M**.
- **Watchlist/Favourites upgrades**: sort, in-list search, export/import [Both] **S–M**.
- **Cloud sync** of profiles/favourites/resume across devices (Firebase) [Both] **L** — natural **Pro** feature.

## Phase 5 — MyKiddieTv moat (invest — almost no competition)
- **Screen-time limits & schedules** (e.g., 1 hr then lock; bedtime mode) [K] **M** — top parent draw.
- **Per-kid profiles** with age bands + individual whitelists [K] **M**.
- **Parent watch-history report** ("what did my kid watch") [K] **S–M**.
- **Curated educational / age-rated rails**; bigger kid-first UI [K] **M**.
- **True kiosk lockdown** (device-owner) option for shared tablets [K] **M**.

## Phase 6 — Launch readiness
- **Release (signed) build** + crash reporting [Both] **S**.
- **Privacy policy** (creds stay on-device — easy story) + store listing assets (V brand, screenshots) [Both] **S**.
- **Amazon Appstore / Fire TV submission** first (Google Play removes IPTV players; keep it strictly a *player with no bundled content*) [Both] **M**.
- **Free + Pro tiering** (Pro = external EPG, multi-view, cloud sync, more profiles) — revenue without paywalling core playback [Both] **M**.

---

## Suggested execution order
1. **Phase 0** (fast polish — momentum + demo-ability)
2. **Phase 1 EPG** (the headline; reminders + catch-up depend on it)
3. **Phase 2 Xtream/M3U** (expand reach before launch)
4. **Phase 3 reliability** + **Phase 5 kid screen-time** (the two moats)
5. **Phase 6 launch**; fold in **Phase 4 sync** as the first Pro feature.

## Dependencies & notes
- Reminders & solid catch-up **depend on** Phase 1 EPG.
- Cloud sync, recommendations-at-scale, and any account portal **need a backend** (Firebase) — keep optional/Pro.
- Platform realities: Fire OS has no Google services (Cast is inert there); Amazon Appstore is the friendlier store for IPTV players.
- Every change ships to **both apps** unless tagged [V] or [K]; MyKiddieTv keeps the kid graft (profile gate, passcode, whitelist, lock-task).
