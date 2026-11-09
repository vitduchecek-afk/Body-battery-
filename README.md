# Charge for COROS

Android app with a home-screen widget that calculates a daily **Charge** value (0–100) from
COROS watch data, read through the official COROS MCP gateway. Independent project, not
affiliated with COROS or Garmin. Data stays on the phone.

- `core/` – pure Kotlin: COROS MCP login, data parsing, charge model, VO2 max estimate (+ tests,
  including a check that all translations contain every string)
- `app/` – Android app, widget, daily notification, 11 languages
- The APK is built by GitHub Actions and published in Releases as `latest` (`ChargeForCOROS.apk`).
  A second job runs instrumented tests on an Android 14 emulator.

Local core tests (no Android SDK needed): `BB_CORE_ONLY=1 gradle :core:test`
