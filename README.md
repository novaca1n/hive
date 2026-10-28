# Hive

An Apple Watch–style honeycomb home screen for the CMF Phone 2 Pro — a tweak for
[derealization](https://github.com/novaca1n/derealization), installed from
[TweakStore](https://github.com/novaca1n/tweakstore).

- All apps as round icons in a honeycomb spiral from the center; icons near the screen edges shrink like on the watch
- Drag in any direction with momentum and rubber-band edges, pinch to zoom
- The honeycomb grows ring by ring after unlocking; tapping an app zooms into its icon while the app opens from it
- Icons are the same as on your Nothing home screen (themed, with derealization's colors) — the Nothing launcher
  sends them to Hive through derealization. Without derealization, Hive draws its own round icons
- Your wallpaper behind it; long-press an icon for app info / uninstall

Hive is a launcher: after installing, choose it as the home app (Settings → Apps → Default apps → Home app). The
Nothing launcher keeps working in the background for gestures and recents.

**Preview (0.1).** Known issue: right after unlocking, the screen can stay black for about a second before the
honeycomb appears.

## Build

```sh
./build.sh assembleRelease
```

JDK 21, Android SDK 37, no dependencies. Release signing reads `../keys/keystore.properties` (or `$RELEASE_KEYS`).

## License

GPL-3.0 — see [LICENSE](LICENSE).
