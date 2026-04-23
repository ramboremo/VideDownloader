{com.videdownloader.app/com.cognitivechaos.xdownload.MainActivity} does not exist# Requirements Document

## Introduction

The app is being rebranded from "VideDownloader" to "XDownload" / "XDownloader - Fast Video Downloader". All user-visible names, internal identifiers, class names, and documentation references to the old brand must be updated consistently across the codebase. This includes renaming the Android package identifier from `com.videdownloader.app` to `com.cognitivechaos.xdownload` across all Gradle build files, Kotlin source files, import statements, AndroidManifest.xml, and test files.

## Glossary

- **App**: The Android application being rebranded.
- **Short_Name**: The concise brand label shown on the device home screen — "XDownload".
- **Full_Name**: The complete Play Store listing name — "XDownloader - Fast Video Downloader".
- **Old_Name**: The previous brand label "VideDownloader", used only to identify targets for replacement.
- **Package_ID**: The Android application identifier — `com.cognitivechaos.xdownload`, replacing `com.videdownloader.app`.
- **Old_Package_ID**: `com.videdownloader.app` — the previous Android application identifier being replaced.
- **app_name**: The Android string resource that supplies the launcher label.
- **Application_Class**: The `Application` subclass (`VideDownloaderApp.kt`) whose class name references the Old_Name.
- **Root_Project_Name**: The Gradle `rootProject.name` value in `settings.gradle.kts`.
- **DB_Name**: The Room database file name `"videdownloader.db"` passed in `AppModule.kt`.

---

## Requirements

### Requirement 1: Update User-Visible App Name

**User Story:** As a user, I want the app to display "XDownload" as its name on my device, so that the launcher icon, notifications, and system dialogs reflect the new brand.

#### Acceptance Criteria

1. THE App SHALL set the `app_name` string resource to `"XDownload"`.
2. WHEN the Android system reads the application label, THE App SHALL return the value of `@string/app_name`.

---

### Requirement 2: Rename Application Class

**User Story:** As a developer, I want the `Application` subclass to be named after the new brand, so that the class name is consistent with the new identity.

#### Acceptance Criteria

1. THE App SHALL rename the class `VideDownloaderApp` to `XDownloadApp` in `VideDownloaderApp.kt`.
2. THE App SHALL update the `android:name` attribute in `AndroidManifest.xml` to reference `.XDownloadApp`.
3. THE App SHALL rename the source file from `VideDownloaderApp.kt` to `XDownloadApp.kt`.

---

### Requirement 3: Update Gradle Root Project Name

**User Story:** As a developer, I want the Gradle project name to reflect the new brand, so that build outputs and IDE project labels are consistent.

#### Acceptance Criteria

1. THE App SHALL set `rootProject.name` to `"XDownloader"` in `settings.gradle.kts`, replacing `"VideDownloader"`.

---

### Requirement 4: Update Database File Name

**User Story:** As a developer, I want the Room database file name to reflect the new brand, so that internal identifiers are consistent with the new identity.

#### Acceptance Criteria

1. THE App SHALL use the database file name `"xdownloader.db"` in `AppModule.kt`, replacing `"videdownloader.db"`.

---

### Requirement 5: Rename Android Package ID

**User Story:** As a developer, I want the Android application package identifier updated to `com.cognitivechaos.xdownload`, so that the app's identity is fully aligned with the new brand.

#### Acceptance Criteria

1. THE App SHALL set `applicationId` to `"com.cognitivechaos.xdownload"` in `app/build.gradle.kts`, replacing `"com.videdownloader.app"`.
2. THE App SHALL set `namespace` to `"com.cognitivechaos.xdownload"` in `app/build.gradle.kts`, replacing `"com.videdownloader.app"`.
3. THE App SHALL update every Kotlin source file `package` declaration from `com.videdownloader.app` (and its sub-packages) to the corresponding `com.cognitivechaos.xdownload` sub-package.
4. THE App SHALL update every `import` statement that references `com.videdownloader.app` to reference `com.cognitivechaos.xdownload` instead.
5. THE App SHALL update all package references in `AndroidManifest.xml` from Old_Package_ID to Package_ID.
6. THE App SHALL apply the same package declaration and import updates to all files under `app/src/test/`.

---

### Requirement 6: Update Documentation and Comments

**User Story:** As a developer, I want all comments and documentation in the codebase to use the new brand name, so that the codebase is internally consistent.

#### Acceptance Criteria

1. THE App SHALL replace every occurrence of the Old_Name in inline code comments with the Short_Name or Full_Name as contextually appropriate.
2. THE App SHALL replace the Old_Name reference in `hello.txt` with the Short_Name.
3. THE App SHALL replace Old_Name references in existing spec documents (`.kiro/specs/`) with the Short_Name where the old name appears as a product label rather than as a historical identifier.
