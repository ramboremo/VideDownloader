# Design Document: App Rebranding (VideDownloader → XDownload)

## Overview

This document describes the technical approach for rebranding the Android app from "VideDownloader" to "XDownload" / "XDownloader - Fast Video Downloader". The change is a mechanical rename across build configuration, Kotlin source files, the Android manifest, and string resources. No functional behavior changes. No database migration is needed since the app has not been published.

The scope covers:
- User-visible name (`app_name` string resource)
- Android package identifier (`applicationId`, `namespace`, all `package` declarations and `import` statements)
- Application class name and file name
- Gradle root project name
- Room database file name
- Download folder name and notification title in `DownloadService`
- Intent action strings in `DownloadService`
- Inline comments and documentation

Theme identifiers (`Theme.VideDownloader`, `VideDownloaderTheme`) are explicitly **out of scope** and must not be changed.

---

## Architecture

The app follows a standard Android single-module architecture with Hilt for dependency injection, Room for persistence, and Jetpack Compose for UI. The rebranding touches the following layers:

```
settings.gradle.kts          ← rootProject.name
app/build.gradle.kts         ← namespace, applicationId
AndroidManifest.xml          ← android:name (Application class), action strings
res/values/strings.xml       ← app_name
Kotlin source files          ← package declarations, imports, class names
  ├── VideDownloaderApp.kt   ← rename class + file → XDownloadApp.kt
  ├── service/DownloadService.kt ← ACTION_* strings, folder name, notification title
  └── di/AppModule.kt        ← DB file name
```

No architectural changes are required. All changes are identifier/string substitutions.

---

## Components and Interfaces

### 1. String Resources (`res/values/strings.xml`)

| Before | After |
|--------|-------|
| `<string name="app_name">VideDownloader</string>` | `<string name="app_name">XDownload</string>` |

### 2. Build Configuration

**`settings.gradle.kts`**

| Before | After |
|--------|-------|
| `rootProject.name = "VideDownloader"` | `rootProject.name = "XDownloader"` |

**`app/build.gradle.kts`**

| Before | After |
|--------|-------|
| `namespace = "com.videdownloader.app"` | `namespace = "com.cognitivechaos.xdownload"` |
| `applicationId = "com.videdownloader.app"` | `applicationId = "com.cognitivechaos.xdownload"` |

### 3. AndroidManifest.xml

| Before | After |
|--------|-------|
| `android:name=".VideDownloaderApp"` | `android:name=".XDownloadApp"` |

No other manifest attributes reference the old package ID directly (the `package` attribute is derived from `namespace` in AGP 7+).

### 4. Application Class

| Before | After |
|--------|-------|
| File: `VideDownloaderApp.kt` | File: `XDownloadApp.kt` |
| Class: `VideDownloaderApp` | Class: `XDownloadApp` |
| Package: `com.videdownloader.app` | Package: `com.cognitivechaos.xdownload` |

### 5. All Kotlin Source Files

Every `.kt` file under `app/src/main/java/com/videdownloader/` and `app/src/test/java/com/videdownloader/` must have:
- `package com.videdownloader.app[.subpackage]` → `package com.cognitivechaos.xdownload[.subpackage]`
- All `import com.videdownloader.app.*` → `import com.cognitivechaos.xdownload.*`

The source directory tree itself (`com/videdownloader/app/`) must be renamed to `com/cognitivechaos/xdownload/`.

### 6. DownloadService

| Location | Before | After |
|----------|--------|-------|
| `ACTION_START` | `"com.videdownloader.ACTION_START"` | `"com.cognitivechaos.ACTION_START"` |
| `ACTION_PAUSE` | `"com.videdownloader.ACTION_PAUSE"` | `"com.cognitivechaos.ACTION_PAUSE"` |
| `ACTION_RESUME` | `"com.videdownloader.ACTION_RESUME"` | `"com.cognitivechaos.ACTION_RESUME"` |
| `ACTION_CANCEL` | `"com.videdownloader.ACTION_CANCEL"` | `"com.cognitivechaos.ACTION_CANCEL"` |
| `getDownloadDirectory()` folder | `"VideDownloader"` | `"XDownload"` |
| `buildNotification()` title | `"VideDownloader"` | `"XDownload"` |
| Sync gallery public folder | `"VideDownloader"` | `"XDownload"` |

### 7. AppModule — Database Name

| Before | After |
|--------|-------|
| `"videdownloader.db"` | `"xdownloader.db"` |

No Room migration is needed because the app has not been published and there is no existing user data to preserve.

---

## Data Models

No data model changes. The rename of the DB file name is a configuration-only change. Room entity and DAO definitions are unaffected.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

Most acceptance criteria for this feature are SMOKE checks (one-time configuration verification). However, two universal properties emerge from the package and brand-name rename requirements: the old identifiers must be absent from **all** source files, not just a known list. These are well-suited to property-based verification because the set of files is enumerable and the property must hold for every member of that set.

### Property 1: No old package identifier in any Kotlin source file

*For any* `.kt` file in the project source tree (`app/src/`), the file content SHALL NOT contain the string `com.videdownloader.app` in any `package` declaration or `import` statement.

**Validates: Requirements 5.3, 5.4, 5.6**

### Property 2: No old brand name in any source file comment or string literal

*For any* `.kt` file in the project source tree, the file content SHALL NOT contain the string `"VideDownloader"` as a brand label in inline comments or string literals (excluding theme identifiers `Theme.VideDownloader` and `VideDownloaderTheme` which are out of scope).

**Validates: Requirements 6.1**

---

## Error Handling

This feature involves no runtime error handling changes. The only risk is an incomplete rename leaving stale references that cause a compile error or runtime crash:

- **Compile-time safety net**: Any missed `package` or `import` referencing `com.videdownloader.app` will cause a Kotlin compilation failure, making incomplete renames immediately visible.
- **Runtime risk**: The DB file name change (`videdownloader.db` → `xdownloader.db`) will cause Room to create a new empty database on first launch. This is acceptable because the app is not yet published and there is no user data to migrate.
- **Intent action strings**: The `ACTION_*` constants are only used internally within the app (the service is `android:exported="false"`), so changing them carries no backward-compatibility risk.

---

## Testing Strategy

This feature is a mechanical rename with no new logic. The testing approach focuses on verifying completeness of the rename rather than functional correctness.

### PBT Applicability Assessment

Most criteria are SMOKE checks (configuration values, file existence, manifest attributes). Two criteria — the exhaustive absence of old package identifiers and old brand names across all source files — are universal properties over the set of files, making them suitable for property-style enumeration tests.

A property-based testing library is not required here; the "property" is verified by enumerating all files and asserting the absence of forbidden strings. This is implemented as a parameterized or loop-based unit test.

### Unit / Smoke Tests

These verify specific configuration values after the rename:

1. `strings.xml` contains `app_name = "XDownload"`
2. `AndroidManifest.xml` has `android:name=".XDownloadApp"`
3. `AndroidManifest.xml` has no references to `com.videdownloader`
4. `app/build.gradle.kts` has `applicationId = "com.cognitivechaos.xdownload"` and `namespace = "com.cognitivechaos.xdownload"`
5. `settings.gradle.kts` has `rootProject.name = "XDownloader"`
6. `AppModule.kt` uses `"xdownloader.db"`
7. `XDownloadApp.kt` exists; `VideDownloaderApp.kt` does not
8. `DownloadService.kt` ACTION constants use `com.cognitivechaos` prefix
9. `DownloadService.kt` folder name and notification title use `"XDownload"`

### Property Tests (Enumeration-Based)

**Property 1 test** — Enumerate all `.kt` files under `app/src/`. For each file, assert it does not contain `com.videdownloader.app`. This catches any file missed during the rename.

**Property 2 test** — Enumerate all `.kt` files under `app/src/`. For each file, assert it does not contain the string `"VideDownloader"` outside of the excluded theme identifiers.

These tests act as a regression guard: if any future edit accidentally reintroduces the old name, the test fails immediately.

### Build Verification

The primary correctness signal for this feature is a successful Gradle build (`./gradlew assembleDebug`). Any missed `package` or `import` reference will produce a compile error. Running the build after the rename is the most important verification step.
