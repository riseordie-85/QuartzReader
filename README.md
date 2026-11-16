# Quartz Reader

One Android app for ebooks, comics, manga and manhwa, on themed shelves. Part of the Quartz family.

- **Library:** one folder sorted into Books, Comics, Manga and Manhwa, with series, shelves and search.
- **Reading:** EPUB, CBZ, CBR, CB7, ZIP, PDF and folders of images, with highlights, notes and bookmarks.
- **Stats:** reading time, streaks, goals, achievements, recaps and reminders.
- **Sync:** CrossPoint and KOReader progress sync, highlight sync with CrossPoint, and a backup file in
  the library folder.
- **Privacy:** no ads, no analytics and no accounts.

Downloads and more are on the [Quartz site](https://quartz.timcordova.com/reader/). See
[CHANGELOG.md](CHANGELOG.md) for what's in each version. Needs Android 13 or newer.

## Build

Open the project in Android Studio and build the **release** variant, since Compose is much slower in
debug builds.

Release builds are signed with the key described in `keystore.properties` in the project folder. That
file and the key never go in the repo. Without it, release builds use this computer's debug key.

```
storeFile=/path/to/quartz-release.jks
storePassword=...
keyAlias=quartz
keyPassword=...
```

To make a release, raise `versionCode` and `versionName` in `app/build.gradle.kts`, add the version to
`CHANGELOG.md`, build the release APK, and attach it to a GitHub release tagged with the version, such
as `v1.0.0`. The Quartz site shows the newest release after its next build, which runs daily or from
**Run workflow** on the site's Actions tab.

Book files never go in this repo.

## Test tools

**Settings > About > Test tools** opens files with timing, runs the shelf scrolling test, and checks how
fast the library folder is. **Copy** puts the results on the clipboard.

## Art

`art/backgrounds` holds Tim's original SVG backgrounds for the style editor. Their backgrounds are
transparent, so they work over any color.
