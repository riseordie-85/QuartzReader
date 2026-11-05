# Quartz Reader

One Android app for ebooks, comics, manga, and manhwa. This repo is at Phase 0: a bare test app that
proves the riskiest parts on real devices before the real app is built on them.

## Build

Open the project in Android Studio. Set the build variant to **release**, because Compose is much
slower in debug. Install on each test device.

Book files never go in this repo.

## Phase 0 tests

1. **Open a test file:** pick a CBZ, CBR, CB7, ZIP, PDF, EPUB, or MOBI. In the image reader, try every
   mode, then tap **Speed test**.
2. **Open a folder of images:** pick an unzipped manhwa folder, then tap **Speed test**.
3. **Shelf test:** swipe and scroll by hand, then tap **Run test**.
4. **Library folder speed test:** pick the folder you'd use as a library. It creates a test folder
   inside it and deletes that folder when it finishes.
5. **Results:** tap **Copy** and paste the results into the chat.
