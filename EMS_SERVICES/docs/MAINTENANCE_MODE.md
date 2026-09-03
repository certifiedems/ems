# Maintenance mode

How to take the platform down deliberately for a deploy, a migration, or any
other work that needs the API quiet — and what users see while it is down.

There are two independent mechanisms. They cover different failures and both are
usually wanted.

| | Who decides | What it covers | How to change it |
|---|---|---|---|
| **Automatic outage detection** | The frontend, on its own | The API being unreachable — a crash, a restart, a cold start, a dropped connection | Nothing to configure; always on |
| **Declared maintenance** | You, via an env var | Planned work where the API is up but must not be used | `MAINTENANCE_MODE` on the backend |

---

## 1. Automatic outage detection (no configuration)

The UI watches the API without polling it. Two signals feed the same state:

- **The app's own traffic.** `apiClient`'s response interceptor reports every
  network failure and every 502/503/504 through `src/api/serverHealth.js`. A
  user's failing request is the fastest possible outage signal — faster than any
  polling interval.
- **A liveness probe** against `GET /api/system/status`, run to confirm what the
  traffic suggested, and then on a backoff (2s → 30s) until the API answers.

Two consecutive failures are required before the screen appears. One is not
enough: the API is hosted on a platform that suspends idle instances, so the
first request after a quiet period routinely times out while the container
wakes. The screen clears itself the moment the API answers — no refresh.

Nothing polls while the service is healthy.

### What it deliberately ignores

Only 502, 503 and 504 count as outages. A 500 is one endpoint failing, a 401 is
a session problem, a 404 is a bad path — none of them are reasons to throw a
full-screen outage over a working application.

---

## 2. Declared maintenance (the switch you throw)

### Backend — preferred

Set these on the host (Render → Environment) and restart:

```bash
MAINTENANCE_MODE=true
MAINTENANCE_MESSAGE="Upgrading the exam database. Nothing you have submitted is affected."
MAINTENANCE_ETA="about 30 minutes"          # free text, optional
MAINTENANCE_RETRY_AFTER_SECONDS=60          # optional, default 60
```

While it is on:

- `/api/**` answers **503** with a `Retry-After` header.
- `/api/system/status` stays open and reports the reason, which is what the UI
  renders on screen.
- `/api/auth/login`, `/api/auth/refresh-token` and `/api/auth/logout` stay open,
  and **administrators bypass the gate entirely** — so the team can sign in and
  verify the system before reopening it.
- `/actuator/**` stays open, so the platform's health checks do not read planned
  maintenance as a crashed instance and start cycling the container.

To lift it: unset `MAINTENANCE_MODE` (or set it to `false`) and restart. Open
tabs return to the app on their own within one retry interval.

### Frontend — only when the backend cannot speak for itself

```bash
VITE_MAINTENANCE_MODE=true
VITE_MAINTENANCE_MESSAGE="We are rolling out an update."
```

This holds everyone on the maintenance screen regardless of what the backend
says, and it disables probing entirely. Vite inlines `VITE_*` at build time, so
**it requires a rebuild and redeploy to both set and unset** — which is why it is
the second choice. Reserve it for work the backend cannot announce because it is
the thing being replaced: a host migration, a DNS cutover, an API swap.

---

## What users see

`src/pages/system/MaintenancePage.jsx` renders three distinct situations,
because conflating them sends people to the wrong remedy:

| State | Headline | When |
|---|---|---|
| `maintenance` | We will be right back | Declared, from either switch |
| `down` | Reconnecting to the server | API unreachable |
| `offline` | You appear to be offline | `navigator.onLine` is false |

An operator-supplied `MAINTENANCE_MESSAGE` always replaces the generic copy.

### The one exception: a live exam

`/exam/:applicationId` is **never** taken over. Replacing a running exam with a
full-screen notice would unmount the webcam, the proctoring monitors and the
answer state held in that tree — turning a thirty-second backend hiccup into a
lost attempt on an exam the candidate cannot repeat without paying again.

Candidates mid-exam get a fixed banner instead. Their answers are genuinely safe:
`useExamAutosave` writes to `localStorage` synchronously on every change and
flushes to the server when the connection returns.

The sibling routes (`/exam/schedule/:id`, `/exam/payment/:id`,
`/exam/result/:id`) all carry a second path segment and are covered by the
takeover like any other page.

---

## Verifying

```bash
# Up
curl -s localhost:8080/api/system/status
# {"data":{"status":"UP","maintenance":false,...}}

# Closed
MAINTENANCE_MODE=true mvn spring-boot:run -Dspring-boot.run.profiles=h2
curl -s localhost:8080/api/system/status      # 200, status MAINTENANCE
curl -s -i localhost:8080/api/dashboard/me    # 503 + Retry-After
curl -s -i localhost:8080/api/auth/login      # not 503 — stays open for admins
```
