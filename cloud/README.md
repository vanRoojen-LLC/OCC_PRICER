# CardBox Trading Cloud (MVP)

The multi-store web version of CardBox Trading (formerly OCC Pricer), live at **https://cardbox.trading**. One container serves the React client
and the API; PostgreSQL holds the data.

- **Free price check** at `/`: search a Magic, Star Wars: Unlimited or other public game's card and see its market price. No account, as Scryfall's terms require.
- **Store workflow** under `/app` (CardBox sign-in through Auth0, 30-day trial): trade entry with store credit, check or split payouts,
  customers linked by phone number, trade history, tiered buy rates, a store profile, several owners and staff per store,
  multiple locations with every trade tagged to the location it was taken at, inventory per location kept in a storage
  tree each store designs itself (store room, shelf, box, section, or any tiers it likes), an inventory table that filters
  and sorts on every card detail (game, set, year, rarity, color, type, finish, treatment, condition, price, source) and
  moves picked lines or everything matching into a spot at once, storage rules that say what goes in each spot
  ("Magic" on Shelf 2, "Red, names A–L" in Box 1 inside it) so Inventory suggests a spot for every card and lists what
  to file where, and the 19-column receiving POS CSV.
  One CardBox login can belong to several stores and switch between them.
- **Platform admin** at `/app/admin` for the verified owner email: every store and person, plan status, trial end dates,
  renaming stores, adding, promoting or removing people on any store, and Help & feedback reports.
- **Help & feedback** in the store app's top bar: anyone signed in can send a bug, question or idea (see below).

Pricing, condition multipliers, settlement and the POS CSV come from the desktop app's own classes
(`SettlementEngine`, `PricingService`, `TradePosEncoder`, ...), compiled directly from `../src` (see `api/pom.xml`),
so web and desktop produce the same offers and cent allocations.

## Layout

| Path | What |
|---|---|
| `api/` | Spring Boot 3.5 on Java 21, JDBC + Flyway (`api/src/main/resources/db/migration`) |
| `web/` | React + Vite client |
| `Dockerfile` | Builds both into one image; build from the repo root |
| `infra/main.bicep` | Azure resources |
| `deploy.sh` | Deploys to Azure with the Azure CLI |

## Run locally

```sh
docker run -d --name occpg -e POSTGRES_USER=occ -e POSTGRES_PASSWORD=occ -e POSTGRES_DB=occ -p 5432:5432 postgres:17-alpine
cd cloud/api
export APP_SESSION_SECRET=dev-secret-dev-secret-dev-secret-012345 APP_SECURE_COOKIE=false
export AUTH0_DOMAIN=dev-tnnibhkgdbepzjy1.us.auth0.com AUTH0_CLIENT_ID=i8rRy5TlNKCvd4tMFWOqMkJRY1PhCPvq AUTH0_CLIENT_SECRET=<its secret>
mvn -DskipTests package
java -jar target/cardbox-trading-cloud.jar import-catalog            # downloads Scryfall bulk data (~500 MB)
java -jar target/cardbox-trading-cloud.jar                           # API on :8080
cd ../web && npm install && npm run dev                          # client on :5173, proxies /api to :8080
```

`import-catalog --app.catalog.file=src/test/resources/cards-fixture.json` loads a five-card fixture instead of Scryfall.
`mvn verify` runs the integration tests against PostgreSQL in Docker.

## Card data sources

The nightly `import-catalog` job blends several sources, each logged as its own run in `catalog_imports` (source = the
base URL read, game = the game or `tcgtracking`):

- **Magic**: Scryfall's bulk data (prices are TCGplayer's, as Scryfall reports them).
- **Star Wars: Unlimited**: the catalog from swu-db (api.swu-db.com, as CardBox Club uses). TCGplayer prices come from
  **TCGTracking** first (`/v1/79/sets`, then each set's `/pricing`), and from **TCGCSV** (tcgcsv.com) only if
  TCGTracking fails; swu-db's own price fills any gap.
- **Every other game TCGplayer lists**: TCGTracking's free Open TCG API (`app.tcgtracking.base`, default
  https://openapi.tcgtracking.com/v1), static JSON re-hosting TCGplayer's catalog and prices, into `tcg_games`,
  `tcg_sets` and `tcg_products` (one row per product per price subtype, card ids stable across imports, 1000px images).
  Each game is keyed by its CardBox Club segment (`pokemon`, `lorcana`, ...; Pokemon Japan shares `pokemon`).

TCGTracking is a free service, so the sync is gentle: one request at a time, `app.tcgtracking.pause-ms` (1 s) apart,
each conditional on the stored ETag; the category and per-game set listings once a run; a set's cards only when its
`products_modified` moves, its prices when `pricing_modified` moves or our copy is over 72 hours old (the listings are
edge-cached for days). Each run stops after `app.tcgtracking.budget-minutes` (20), most-stale sets first, so the first
full sync (~3,400 sets) takes several nights and later nights fetch only what changed. A failed set is retried the next
night. The job starts at 15:00 UTC, after TCGTracking's price refresh around 9:35 AM ET (in both EDT and EST), so each
night reads that day's prices.

Each night's prices also go into `price_history` (source `tcgplayer via tcgtracking` for TCGplayer's price served by
TCGTracking, `tcgplayer` when TCGCSV or Scryfall served it): every SWU printing, and the Magic and TCGTracking cards a
store holds or has traded. Sets holding such a card have their TCGTracking prices read daily.

For those held and traded cards, every game Magic and SWU included, the night also reads each holding set's
TCGTracking `/skus` file (`TcgSkuPrices`, `tcg_sku_prices`, V28; at most `app.tcgtracking.sku-max-sets`, 600, a night,
same pause): TCGplayer's market, low and high **per condition** (NM, LP, MP, HP, DMG) and language, the **active
listing count**, and **Mana Pool's** price. The evidence panel lists the English condition prices with their listing
counts and warns when a card has fewer than 3 near-mint listings ("thin market"). Mana Pool's near-mint English
price goes into `price_history` as `manapool via tcgtracking`, an independent origin the blend weighs beside TCGplayer.

New games arrive as **previews**: only the Preview list sees them (`GET /api/app/games`, and trade and stock search).
The Preview list is CardBox Club's, one list for both apps: platform owners always, plus everyone the owner gives the
`preview_access` role on Club (Settings > Segments, "Who sees Preview", or the Users page). Trading copies it from
Club at each sign-in and whenever the Admin tab reads roles (`cardbox_tokens.preview`, V27).
Setting `tcg_games.preview = false` puts a game on the free price check (`GET /api/public/games`) and in every store's
search; `enabled = false` stops syncing and searching it.

## Azure

Everything lives in one resource group. The Azure resources and the image repository (`occ-pricer`) keep their original OCC Pricer names on purpose: renaming them is a redeploy, not a label change. The jar (`cardbox-trading-cloud.jar`) and the application class carry the CardBox Trading name.

| Resource | SKU | Purpose |
|---|---|---|
| Container App `occpricer-app` | Consumption, 0.5 vCPU / 1 GiB, one replica always on | Web client + API |
| Container Apps Job `occpricer-catalog-import` | Consumption, daily 15:00 UTC | Scryfall, swu-db and TCGTracking imports |
| PostgreSQL Flexible Server | Burstable B1ms, 32 GB | Data |
| Container Registry | Basic | Images, built with `az acr build` |
| Key Vault | Standard | Database password and session signing key |
| Log Analytics | Pay as you go, 0.5 GB/day cap | Logs |

Deploy or update (idempotent):

```sh
az login --use-device-code --tenant b5a8b81b-a80c-4aaa-b3cc-2e54736c0fe4
cloud/deploy.sh
```

### Sign-in (Auth0)

Store sign-in uses Auth0 Universal Login in the same Auth0 tenant as cardbox.club, so a person has one CardBox login
for both sites. cardbox.trading has its own Auth0 application, **CardBox Trading** (Regular Web Application), so its
client id (`i8rRy5TlNKCvd4tMFWOqMkJRY1PhCPvq`) and secret can be rotated or switched off without touching cardbox.club. It has the same connections as the CardBox application (Username-Password and Google).

| Setting | Value |
|---|---|
| Allowed Callback URLs | `https://cardbox.trading/api/auth/callback`, `https://www.cardbox.trading/api/auth/callback`, `http://localhost:5173/api/auth/callback`, `http://localhost:8080/api/auth/callback` |
| Allowed Logout URLs | `https://cardbox.trading/`, `https://www.cardbox.trading/`, `http://localhost:5173/`, `http://localhost:8080/` |
| Allowed Web Origins | `https://cardbox.trading`, `https://www.cardbox.trading` |
| Grant types | Authorization Code (with PKCE), no refresh tokens |
| ID token signing | RS256 (Advanced Settings > OAuth). Apps created through the Management API default to HS256, which the app rejects |
| Connections | The same ones the CardBox application uses |

How the app treats a sign-in (`api/.../auth/AuthController.java`):

- Authorization Code + PKCE with scope `openid email profile`; `state` and `nonce` are checked, and the ID token is
  validated against the tenant's keys, issuer and client id.
- Only verified emails are accepted.
- Users are keyed on the Auth0 user id (`users.auth0_sub`), the same id cardbox.club stores. The first sign-in falls
  back to the verified email, which is how accounts made before Auth0 and staff an owner added by email get linked.
- Someone with no account yet is asked to name their store, which starts its trial with them as owner.
- Sign-out clears the app's session and then Auth0's (`/v2/logout`).
- The platform owner is the verified `OWNER_EMAIL` (`toby@vanroojen.com`), reported as `admin` by `/api/auth/me`.
  It is separate from owning a store.

Settings: `AUTH0_DOMAIN` and `AUTH0_CLIENT_ID` are Bicep parameters (`auth0Domain`, `auth0ClientId`); the client secret
is the Key Vault secret `auth0-client-secret`, which `deploy.sh` requires and never generates:

```sh
auth0 apps show <client id> --reveal-secrets --json | jq -r .client_secret \
  | az keyvault secret set --vault-name <vault> -n auth0-client-secret --file /dev/stdin -o none
```

If Universal Login later moves to a shared custom domain such as `login.cardbox.club`, set `auth0Domain` to it here
and on cardbox.club, and signing in on one site signs you in on the other.

### People, stores and roles from CardBox (switched off)

cardbox.club holds the one copy of people, stores and role assignments (`platform_owner`, `store_manager`,
`store_employee`), and both sites read and write it. Trading keeps only its own business data (plan, locations,
inventory, rates, trades) for each CardBox store. This is built but off until CardBox confirms its side is live.
It is the `cardboxEnabled` Bicep parameter (`CARDBOX_ENABLED`, default `false`); `CARDBOX_ENABLED=true cloud/deploy.sh`
turns it on.

With it on:

- Login asks Auth0 for an access token for `https://cardbox.club/api` as well. The server keeps it, encrypted, in
  `cardbox_tokens` (never in the browser) and calls CardBox with it as the signed-in person.
- Each sign-in calls `POST /api/partner/sign-in` and copies the answer onto Trading's rows: `store_manager` becomes
  owner and `store_employee` staff of the Trading store tied to that CardBox store (`tenants.cardbox_store_id`), and
  roles CardBox no longer lists end here. A CardBox store seen for the first time is tied to the person's existing
  Trading store of the same name, so its data carries over, or else gets a new Trading store in trial. A 403 means
  no CardBox account, and someone with no store role can't sign in to the store app.
- The Team tab shows only the open store's team (`/api/cardbox/team*`, `cardbox/TeamController.java`, calling
  CardBox's `/api/stores/{id}/team`, `/invites` and `/members`). It never lists CardBox accounts outside the store.
  New people join by email invite: CardBox emails a single-use link (14 days), and the person accepts on
  cardbox.club with a new or existing CardBox account, even one under a different email; that account is then their
  login here. Managers resend or withdraw invites, set a job title, change a role, disable or re-enable access and
  remove people; CardBox enforces that a store manager handles employees and a platform owner handles managers. A
  disable or removal ends the person's membership row here at once. A platform owner opens any store's team from
  the Admin tab (`?store=`).
- The Admin tab uses `/api/cardbox/*`, which forwards only account/roles, stores, role-catalog and role-events to
  CardBox. CardBox's `detail` messages are shown as they are. Platform owner roles and CardBox accounts are managed
  on cardbox.club.
- Trading's own team changes and store sign-up are refused (409). Plans and trials stay Trading's.
- Store names: everything (sign-in, roles, the sync, URLs) goes by the CardBox store id, never the name, so a store
  can be renamed safely. A store tied to CardBox keeps CardBox's name. A platform owner renames it from the Store page
  (Trading passes it to CardBox's `PATCH /api/stores/{id}`) or the Admin tab; store managers see it read-only. A
  rename made on cardbox.club reaches Trading at once through the sync (`PUT /api/partner/club-sync/stores/{id}`, see
  CLUB_SYNC.md 5c), and otherwise at the next sign-in or Team/Admin screen load. A store not tied yet is renamed on
  Trading alone; tie it from the Admin tab, since sign-in's tie-by-name only works while the names still match.
- The platform owner is `OWNER_EMAIL` or anyone CardBox says is a `platform_owner`.
- Trading never calls the Auth0 Management API or writes `app_metadata`; CardBox does that.

Before switching it on, the Auth0 API `https://cardbox.club/api` must exist and allow the CardBox Trading application,
and each Trading store with data should have a matching store on CardBox with its managers. After switching it on,
the Admin tab lists any Trading store that didn't tie itself by name, to tie by hand.


### CardBox collections in store inventory (switched off)

A store manager or employee can mark a collection on cardbox.club "Sync to store", and its cards show up in
that store's inventory here, kept in step. cardbox.club pushes them server to server (`/api/partner/club-sync/*`,
`clubsync/ClubSyncController.java`) with an Auth0 client-credentials token for the audience
`https://cardbox.trading/api` and scope `inventory:sync`. Every item carries Club's version, so repeats and late
deliveries change nothing, and a full snapshot heals drift. Synced cards are their own inventory lines
(`inventory_items.club_link_id`). Club owns how many there are and their condition; Trading owns where they sit. Staff
put synced lines away like any other (whole lines), and that spot is kept across deliveries
(`club_link_items.placed_storage_id`) until a scan on Club sends a new one. An owner picks where each collection's
unplaced cards land and what happens to them when a link ends, from the Inventory page.

It is off until Club's side is ready: `CLUB_SYNC_ENABLED=true cloud/deploy.sh` (Bicep `clubSyncEnabled`). Auth0 is set
up: the "CardBox Trading" API (`https://cardbox.trading/api`, permission `inventory:sync`) is granted to CardBox's
machine-to-machine application, client id `WB4mbh9ky62gjPZHFOHBQCXhYytIie7K`, which is the default allow list (`clubSyncClientIds`,
`CLUB_SYNC_CLIENT_IDS`).
The contract Club builds against is [CLUB_SYNC.md](CLUB_SYNC.md).

With the same machine token, Club's health dashboard reads `GET /api/partner/club-sync/metrics`
(`clubsync/ClubMetricsController.java`): trades in the last 7 days, synced Club items, stores active in the last 30
days (a trade or inventory change), free price checks in the last 7 days (counted per UTC day in
`public_price_checks`; searches answered from a browser or CDN cache are not counted) and the resource group's
month-to-date Azure cost. The cost is asked of Cost Management as the app's managed identity (Bicep grants it Cost
Management Reader on the resource group and sets `AZURE_CLIENT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_RESOURCE_GROUP`)
and kept for an hour; when it cannot be read, `azureMonthToDateUsd` is null and `azureCostError` says why. Any figure
that fails comes back null without failing the rest.

### Help & feedback reports (GitHub filing off until a token is set)

"Help & feedback" in the store app's top bar sends a bug, question or idea to `POST /api/support/reports`, with the
page's route, the app build, the browser's user agent, language and viewport, and the last few uncaught errors in
that tab. The store and person come from the session. Reports are saved in `support_reports` and listed for the
platform owner under **Support reports** on `/app/admin` (`GET /api/admin/support-reports`), with the reporter's
name, email and store. Anyone signed in can send one, even after their store's trial ends; each person can send 10
an hour.

When a GitHub token is set, a job in the app (`support/SupportReportFiler.java`, every 30 seconds while a replica is
up) files each report as an issue in the private CardBox repository (this repository is public, so reports never go here), retrying with backoff up to five times and giving up at once on
401, 403, 404 or 422. The issue still carries no name, email or store
name: just the kind, build, route, browser, viewport, report and store ids, the person's words (email addresses
taken out, @mentions broken), and a link back to the report on the admin page. Its labels are `type:bug`,
`type:support` (questions) or `type:idea`, plus `product:trading`, `surface:web`, `source:user-report` and `needs-triage`; if GitHub
refuses the labels, the issue is filed without them. Without a token, reports are only saved.

| Setting | Default | What |
|---|---|---|
| `GITHUB_ISSUES_TOKEN` | empty (off) | A **fine-grained** personal access token for vanRoojen-LLC/CardBox only, with **Issues: read and write** and nothing else. Vault secret `github-issues-token`. |
| `GITHUB_ISSUES_REPO` | `vanRoojen-LLC/CardBox` | Where issues are filed (Bicep `githubIssuesRepo`). |

To switch filing on, put the token in the vault once (or run `GITHUB_ISSUES_TOKEN=... cloud/deploy.sh`, which stores
it there), then deploy; `deploy.sh` turns on Bicep `githubIssues` whenever the vault has the secret:

```sh
read -rs TOKEN && printf '%s' "$TOKEN" \
  | az keyvault secret set --vault-name <vault> -n github-issues-token --file /dev/stdin -o none
cloud/deploy.sh
```

To switch it off again, delete the secret and redeploy. Reports filed while it was on keep their issue links.

### Domain

`cardbox.trading` is registered at Cloudflare and its DNS is hosted there. Both `cardbox.trading` and
`www.cardbox.trading` are bound to the Container App with free Azure-managed certificates, which Azure renews on its
own; `customDomains` in `infra/main.bicep` keeps the bindings on every deploy. The records, all set to
**DNS only** (grey cloud) because Azure cannot issue or renew the certificates through Cloudflare's proxy:

| Type | Name | Value |
|---|---|---|
| A | `@` | the environment's static IP (`az containerapp env show -g occ-pricer -n occpricer-env --query properties.staticIp`) |
| TXT | `asuid` | the app's verification id (`az containerapp show -g occ-pricer -n occpricer-app --query properties.customDomainVerificationId`) |
| CNAME | `www` | the app's default hostname (`az containerapp show -g occ-pricer -n occpricer-app --query properties.configuration.ingress.fqdn`) |
| TXT | `asuid.www` | the same verification id |

A managed certificate can only be issued after its hostname is on the app, so on a brand-new environment (which also
gets a new IP) deploy once with `customDomains=[]`, update the DNS records, then bind each hostname before redeploying
normally:

```sh
az containerapp hostname add -g occ-pricer -n occpricer-app --hostname cardbox.trading
az containerapp env certificate create -g occ-pricer -n occpricer-env --hostname cardbox.trading \
  --certificate-name cardbox-trading --validation-method HTTP
az containerapp hostname bind -g occ-pricer -n occpricer-app --hostname cardbox.trading \
  --environment occpricer-env --certificate cardbox-trading
# Repeat for www.cardbox.trading with --certificate-name www-cardbox-trading --validation-method CNAME.
```

The certificate names must stay `<hostname with dots as dashes>`, which is what the Bicep expects.

The app keeps one replica running at all times. It used to scale to zero, and the first request after five idle minutes then waited about 30 seconds (scheduling, image pull, JVM start), which visitors saw as a hang and the CardBox status monitor reported as outages. An idle always-on replica is billed at the Container Apps idle rate.

## Not in the MVP yet

Stripe billing (trials are tracked, and an ended trial locks the store workflow),
PostgreSQL row-level security (tenant isolation is enforced in every query and covered by a test),
bounties, trade editing and deletion, receipts as PDF, and importing a store's desktop history.
