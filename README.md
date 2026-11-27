# Lenovo Clock Touch

A [Kiosk Satellite](https://kiosksatellite.com) plugin for the **Lenovo Smart Clock Gen 1** (`sparrow`, MT8167S) that exposes accelerometer-detected taps on the device as Kiosk Satellite **Gesture triggers**, with configurable sensitivity, multi-tap timing, and a live tap tester.

## Features

- **Single, Double, and Triple tap gesture triggers** (`Single tap`, `Double tap`, `Triple tap`)
- **Zero-root tap detection** via the clock's pre-installed Android Things OEM service (`com.google.android.things.sparrow.oem/.SparrowService`)
- **Configurable hardware tap sensitivity (1–10)** tuning the Bosch BMA253 accelerometer over `/dev/i2c-0` (`0x18`)
- **Configurable tap timing window** (`150–1000 ms`, default `450 ms`) for detecting 2nd and 3rd taps
- **Live Tap Tester** on the plugin settings page showing `Tap Detected: Single`, `Double`, or `Triple` with inter-tap timing

## Assigning Tap Actions

This plugin uses Kiosk Satellite's native Gesture triggers:

1. Enable **Lenovo Clock Touch** in **Settings > Plugin Manager**.
2. Open **Settings > Gestures > Add Gesture > Plugin Trigger**.
3. Pick **Single tap**, **Double tap**, or **Triple tap** and assign any Kiosk Satellite or Home Assistant action.

## Testing Sensitivity & Timing

Open the **Lenovo Clock Touch** settings page:

- Adjust **Tap sensitivity** (`1` = firmest, `7` = stock `375 mg`, `10` = lightest `125 mg`) and **Tap timing window**.
- Tap the top of the clock 1, 2, or 3 times — **Tap Tester** displays `Tap Detected: Single`, `Double`, or `Triple` along with the tap gap timing.
- By default, **Pause gestures while testing on this page** keeps your mapped gestures from firing while the settings page is open.

## Enabling Root (SU) for Sensitivity Tuning

Tap detection works out of the box at stock Lenovo sensitivity (`375 mg`, level `7`) without root.

Changing the BMA253 hardware tap sensitivity requires root (SU) enabled for Kiosk Satellite in Magisk:

1. **KS Settings > App Launcher > Enable App Launcher**
2. **Edit Apps > enable Magisk**
3. Enable **Return Automatically when untouched** and set time to **30 seconds**
4. Open **KS slideout menu > Apps > Magisk > SU > + (add) > Kiosk Satellite > BACK** (top right)
5. Wait **30 seconds** to return to KS
6. Return **Return Automatically** and **time** settings to how you choose.

## Building

Requires Python 3, JDK 17+, an Android SDK with `build-tools` (`d8`) and a platform jar, and the Android NDK (`$ANDROID_HOME/ndk` or `$ANDROID_NDK_HOME`).

```sh
python3 tools/test.py
python3 tools/build.py
```

The built package is written to `dist/lenovo-clock-touch-<version>.zip` and can be installed locally via **Settings > Plugin Manager > Developer Tools > Install from ZIP**.

## License

MIT (see `LICENSE`). The compile-only Kiosk Satellite SDK in `sdk/` is Apache-2.0.
