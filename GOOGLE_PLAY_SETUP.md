# Irai Google Play subscriptions — setup checklist

The code is a **development integration**, not a tested release. Do not publish until the purchase flow, refunds, grace periods, and renewals have been tested.

## Play Console products

Create four **auto-renewing subscription products** with these exact IDs and configure monthly base plans/prices in Play Console:

| Product ID | Intended price | Monthly AI messages |
| --- | --- | --- |
| `irai_starter` | $3.00/month | 300 |
| `irai_plus` | $7.99/month | 1,200 |
| `irai_premium` | $14.99/month | 3,000 |
| `irai_ultimate` | $24.99/month | 6,000 |

Prices shown in the Android app come from Google Play, not hardcoded prices. The package/application ID is currently `com.Iraitec`; check that it matches the Play Console app. Make sure products are activated and testers are eligible.

## Backend configuration (Render)

Enable the **Google Play Android Developer API** in a Google Cloud project, grant an appropriate service account access to the app in Play Console, and set the following Render environment variables:

- `GOOGLE_PLAY_PACKAGE`: `com.Iraitec`
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: full JSON of a Google Cloud service-account key, stored as a **secret**. Never commit the key to GitHub.

Backend endpoint `POST /api/google-play/verify` checks purchase tokens against the Android Publisher API, binds the token hash to one Irai account, and attempts acknowledgement. Purchase tokens are not persisted, so later automatic renewal reconciliation and real-time notification handling are **not yet implemented**.

## Testing before release

1. Build the Android project with Gradle and resolve any build or SDK compatibility issues.
2. Upload a signed build to an internal testing track and configure license testers.
3. Test new purchases, already-owned purchases, account restore, canceled subscriptions, expiry, grace periods, account hold, and refunds.
4. Configure authenticated Google Play Real-time Developer Notifications (Cloud Pub/Sub), store tokens encrypted server-side, and reconcile subscription status using Google's API. Until then, access ends at the **last verified expiry**; renewals may require the user to tap Restore.
5. Review the website Stripe billing flow separately from the Google Play app, and ensure users can manage/cancel subscriptions while locked out.
6. Confirm message limits and reset dates. The current server quota resets at **00:00 UTC on the first of each calendar month**, not on each subscriber's billing anniversary.
7. Verify all AI/voice endpoints are covered by server-side authorization. The current backend protects `/api/ai/chat` but additional endpoints must be audited.

The backend currently allows legacy Stripe and promotional entitlements as well as verified Google Play entitlements. Android checkout has been switched to Google Play Billing, while the website Stripe endpoints remain.

**Important:** Until the Android app is rebuilt and deployed, the existing installed APK has not changed. Until Render deploys the new backend and the service account is configured, purchase verification will fail closed.
