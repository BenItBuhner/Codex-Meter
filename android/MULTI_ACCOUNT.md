# Multi-account v1

## Phone ownership and compatibility

`AccountRepository` owns profiles, the selected phone account, scoped credentials,
and cached usage. Profile IDs are locally generated UUIDs; labels are local and
editable. Server account IDs and OAuth subjects are used only when the existing
token response supplies them. Plan and last-success information come from the
normalized usage snapshot, without invented account metadata.

`SecureTokenStore.load/save` and account-specific methods in `AppPreferences`
remain selected-account adapters. Existing widgets, Now Bar, reset-credit
actions, notifications, history, and lock/AOD surfaces use the phone selection.
Changing that selection cancels old account notification/reset surfaces and
updates them from the new account's cache. App-wide appearance and scheduling
settings stay app-wide.

The Accounts page is available from Settings and the dashboard overflow menu.
It lists quotas, supports adding, selecting, renaming, refreshing and removing
accounts, and reports refresh failures per account. Adding a profile preserves
the current default. Authentication imports also add/select a profile rather
than overwriting another account's credentials. Export keeps the existing
explicit, opt-in single selected-account authentication transfer format.

## Encrypted persistence and upgrade

The existing `secure_auth_v1` preferences and Android Keystore key alias
`codex_meter_auth_key_v1` are retained. `accounts_v2` is one AES-GCM encrypted
document containing the profile list, selected UUID, identity keys, and each
profile's token set. Each save uses a fresh IV. An atomic document commit avoids
separate credential/profile index transactions. Removing an account never
deletes the shared Keystore key or another profile's tokens.

On first access, migration reads the legacy encrypted `blob`. A persisted
`migration_id` reserves a UUID so interrupted retries use the same cache
namespace. Legacy settings/cache values are copied into that account's local
usage preferences before the new encrypted document is committed. The old
`blob` and staging ID are removed in the same successful commit that writes
`accounts_v2`. Failed cache copies or encrypted commits leave the old disk
representation available. Once v2 exists, legacy data is never reimported;
removing the last account therefore cannot resurrect it. Global settings and
the original legacy cache remain available, but are no longer used as account
state after migration.

An unreadable encrypted document is preserved and never replaced with an empty
store. Reads show an unavailable/signed-out state; mutations fail without
overwriting it. Loss of the device Keystore key cannot be repaired by migration.

Usage snapshots, errors, reset-credit caches and usage histories live in
`codex_meter_account_<UUID>` preferences. Credentials only live in the encrypted
document. Token rotation, API refresh, adding/reconnecting, selection and removal
are serialized against API requests. Cached reads use a separate short store
lock so background network waits do not block screens.

OAuth retains loopback binding, PKCE, state verification and HTTPS token
exchange. Add-account requests ask the authorization server to show login.
Deduplication requires the OAuth issuer and user subject **and** backend account
ID together. Email or an organization ID alone is never a deduplication key.

## Refresh and Wear

`UsageApi.refreshAndCache(context, accountId)` refreshes an explicit profile.
The existing overload refreshes the phone default. Refresh-all is sequential,
continues after an account fails, and retains each successful cache. Cancellation
stops further requests. The existing JobScheduler operation refreshes all
profiles; there are no per-account background jobs. Detailed reset-credit side
requests remain limited to the phone-selected account.

`WearUsageState` retains its legacy `usage`, `signed_in`, timestamp and source
fields, and adds an account list and phone-selected UUID. `WearAccount` is an
explicit allowlist: local UUID, display label, authentication availability and
normalized usage (including plan, windows, reset times and fetched timestamp).
It has no credential, identity-token, email, header or cookie fields. Arbitrary
backend error messages are not forwarded to Wear. Shared settings and message
trust checks retain their existing contracts.

The watch-local `WearAccountStore` persists a separate override; an empty value
means **Follow phone**. A missing/removed selection falls back to the phone
default, then the first available account. Older single-account payloads still
work. Malformed entries are skipped; malformed cached JSON is safe to replace
with a valid update. Older or duplicate payload revisions are ignored.

All Tiles, complications and the watch monitor read the same
`WearPreferences.loadSnapshot` selection. The existing ProtoLayout cards,
Samsung modular footprint metadata, dial and typography implementation remain
in place. Labels are only added when multiple accounts exist. Compact
complication labels retain an account index when truncation would hide the
distinction between default names such as Account 1 and Account 2. Watch refresh
requests still go to the phone's single refresh job, which refreshes all accounts.

## Validation

`./run-tests.sh` runs existing tests plus `AccountSelfTest`: migration and
interrupted transactions, encrypted-envelope integrity, multiple independent
sessions and token rotation, identity deduplication, selected-account persistence,
removal, explicit/sequential refresh with partial failure and cancellation,
Wear round trips, persisted watch selection, stale/malformed state, shared Tile
selection, compact labels and credential exclusion.

Run `./build.sh` and `./lint.sh` for both phone and Wear. The existing SESL binary
dependencies require JDK 21; the source target remains Java 17. Phone uses SDK 36;
Wear uses SDK 37.0, as configured by the repository.

Live OAuth and visual/device checks require actual accounts and Android/Wear
hardware. Before release, test an upgrade from the old single-account APK with
the **same signing key**, then add two accounts, rotate/expire one token,
refresh all with one account offline, switch phone and watch defaults
independently, remove the watch-selected account, and inspect every Tile and
complication on round Galaxy Watch screens. Locally signed APKs cannot upgrade a
production installation signed with a different key; do not uninstall an
existing production app to test migration.

No CLI switching, account rotation, proxy, external backend, remote token storage,
cloud sync, account sharing or arbitrary API-key authentication is included.
