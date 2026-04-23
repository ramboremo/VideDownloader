# Implementation Plan: App Rebranding (VideDownloader → XDownload)

## Overview

Mechanical rename across build config, Kotlin sources, manifest, and resources. No functional changes. Tasks are ordered so each step compiles cleanly before the next begins.

## Tasks

- [x] 1. Update string resources and build configuration
  - Set `app_name` to `"XDownload"` in `app/src/main/res/values/strings.xml`
  - Set `rootProject.name` to `"XDownloader"` in `settings.gradle.kts`
  - Set `namespace` and `applicationId` to `"com.cognitivechaos.xdownload"` in `app/build.gradle.kts`
  - _Requirements: 1.1, 3.1, 5.1, 5.2_

- [x] 2. Rename Application class and update manifest
  - [x] 2.1 Rename `VideDownloaderApp.kt` → `XDownloadApp.kt` and class `VideDownloaderApp` → `XDownloadApp`
    - Update the `package` declaration in the file to `com.cognitivechaos.xdownload`
    - _Requirements: 2.1, 2.3_
  - [x] 2.2 Update `AndroidManifest.xml`
    - Change `android:name=".VideDownloaderApp"` to `android:name=".XDownloadApp"`
    - Remove or update any remaining `com.videdownloader` references
    - _Requirements: 2.2, 5.5_

- [x] 3. Update all Kotlin source file package declarations and imports
  - [x] 3.1 Update `package` declarations in all `.kt` files under `app/src/main/java/com/videdownloader/`
    - Replace `com.videdownloader.app` (and sub-packages) with `com.cognitivechaos.xdownload`
    - _Requirements: 5.3_
  - [x] 3.2 Update all `import` statements referencing `com.videdownloader.app` across all main source `.kt` files
    - Replace with corresponding `com.cognitivechaos.xdownload` imports
    - _Requirements: 5.4_
  - [x] 3.3 Apply the same package declaration and import updates to all `.kt` files under `app/src/test/`
    - _Requirements: 5.6_

- [x] 4. Rename source directory tree
  - Rename `app/src/main/java/com/videdownloader/app/` → `app/src/main/java/com/cognitivechaos/xdownload/`
  - Rename `app/src/test/java/com/videdownloader/app/` → `app/src/test/java/com/cognitivechaos/xdownload/`
  - _Requirements: 5.3_

- [x] 5. Update DownloadService brand references
  - In `app/src/main/java/com/cognitivechaos/xdownload/service/DownloadService.kt`:
    - Change `ACTION_START`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_CANCEL` string values from `"com.videdownloader.*"` to `"com.cognitivechaos.*"`
    - Change download folder name from `"VideDownloader"` to `"XDownload"`
    - Change notification title from `"VideDownloader"` to `"XDownload"`
    - Change sync gallery public folder name from `"VideDownloader"` to `"XDownload"`
  - _Requirements: 5.3, 5.4, 6.1_

- [x] 6. Update AppModule database name
  - In `app/src/main/java/com/cognitivechaos/xdownload/di/AppModule.kt`:
    - Change `"videdownloader.db"` to `"xdownloader.db"`
  - _Requirements: 4.1_

- [x] 7. Update comments and documentation
  - Replace `"VideDownloader"` brand label in inline comments across all `.kt` files with `"XDownload"` (excluding theme identifiers `Theme.VideDownloader` and `VideDownloaderTheme`)
  - Update `hello.txt` replacing Old_Name with `"XDownload"`
  - _Requirements: 6.1, 6.2_

- [x] 8. Checkpoint — verify the build compiles
  - Ensure all tests pass, ask the user if questions arise.
  - Run `./gradlew assembleDebug` to confirm no stale `com.videdownloader` references remain as compile errors

- [x] 9. Write enumeration tests verifying no old identifiers remain
  - [x] 9.1 Write property test for absence of old package ID in all Kotlin source files
    - Enumerate every `.kt` file under `app/src/`; assert none contains `com.videdownloader.app` in a `package` or `import` statement
    - **Property 1: No old package identifier in any Kotlin source file**
    - **Validates: Requirements 5.3, 5.4, 5.6**
  - [ ]* 9.2 Write property test for absence of old brand name in all Kotlin source files
    - Enumerate every `.kt` file under `app/src/`; assert none contains `"VideDownloader"` as a brand label outside of theme identifiers (`Theme.VideDownloader`, `VideDownloaderTheme`)
    - **Property 2: No old brand name in any source file comment or string literal**
    - **Validates: Requirements 6.1**

- [x] 10. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Theme identifiers (`Theme.VideDownloader`, `VideDownloaderTheme`) are explicitly out of scope — do not change them
- No Room migration is needed; the app has not been published and there is no existing user data
- The primary correctness signal is a successful `./gradlew assembleDebug` — any missed rename produces a compile error
- Property tests in task 9 act as a regression guard against future accidental reintroduction of old names
