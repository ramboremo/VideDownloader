# Requirements Document

## Introduction

VideDownloader's WebView browser intercepts network requests to detect video URLs as the user browses. When the user taps the download FAB, a quality sheet appears with download options. The problem is that pre-roll ad videos are detected alongside main content videos, and ads sometimes rank higher than the actual content — causing the user to accidentally download an ad.

This feature improves the ranking and presentation of detected video candidates so that the main content video is reliably surfaced as the top choice, while still giving the user access to every detected candidate. The guiding principle is: **ranking ≠ filtering**. No detected video is ever hidden or discarded; we only order them by confidence.

## Glossary

- **Ranker**: The component responsible for computing a confidence score for each detected media candidate and ordering the candidate list.
- **Candidate**: A `DetectedMedia` instance that has been detected on the current page.
- **Confidence Score**: A numeric value computed by the Ranker that represents how likely a candidate is to be the main content video the user wants to download.
- **Ad Penalty**: A negative score adjustment applied when a candidate's URL matches known ad-network patterns.
- **Temporal Index**: The zero-based position in which a candidate was detected during the page load (0 = first detected, N = last detected). Later-detected candidates receive a higher temporal bonus.
- **Quality Sheet**: The bottom sheet UI that appears when the user taps the download FAB, showing ranked download options.
- **Top Candidate**: The candidate with the highest Confidence Score in the ranked list.
- **VideoDetector**: The existing service class that intercepts network requests and emits `DetectedMedia` objects.
- **BrowserViewModel**: The existing ViewModel that owns `scoreMediaCandidate()` and drives the Quality Sheet.

---

## Requirements

### Requirement 1: Temporal Detection Ordering

**User Story:** As a user, I want videos that appear later in the page load to be ranked higher, so that pre-roll ads (which load first) are deprioritized relative to the main content video (which loads after the ad).

#### Acceptance Criteria

1. WHEN a `Candidate` is added to the detected list, THE `VideoDetector` SHALL record the detection sequence index (0-based, incrementing per page load) on that candidate.
2. THE `Ranker` SHALL apply a positive score bonus that increases with the detection sequence index, so that later-detected candidates score higher than earlier-detected candidates, all else being equal.
3. WHEN a new page load begins, THE `VideoDetector` SHALL reset the detection sequence counter to zero.
4. THE `Ranker` SHALL weight the temporal bonus so that it can be overcome by other strong signals (e.g., a PH get_media endpoint detected first still outranks a generic MP4 detected last).

---

### Requirement 2: URL Pattern Ad Penalty

**User Story:** As a user, I want URLs that match known ad-network patterns to be ranked lower, so that ad videos don't appear as the top download choice.

#### Acceptance Criteria

1. THE `Ranker` SHALL maintain a list of ad-network URL patterns (e.g., `vast`, `vpaid`, `preroll`, `trafficjunky`, `doubleclick`, `exoclick`, `cdn77-vid`, `magsrv`, `/ads/`, `/ad/`).
2. WHEN a `Candidate`'s URL contains one or more ad-network patterns, THE `Ranker` SHALL apply a negative Ad Penalty to that candidate's Confidence Score.
3. THE `Ranker` SHALL NOT remove a candidate from the list solely because its URL matches an ad-network pattern.
4. IF a `Candidate`'s URL matches an ad-network pattern AND no other candidates are present, THEN THE `Ranker` SHALL still include that candidate in the ranked list so the user can download it.

---

### Requirement 3: File Size Signal

**User Story:** As a user, I want larger video files to be ranked higher, so that the main content (which is typically larger than a short pre-roll ad) is preferred.

#### Acceptance Criteria

1. WHEN a pre-fetched file size is available for a `Candidate`, THE `Ranker` SHALL apply a positive score bonus proportional to the file size.
2. WHEN no pre-fetched file size is available for a `Candidate`, THE `Ranker` SHALL compute the Confidence Score using only the remaining available signals, without penalizing the candidate for the missing size.
3. THE `Ranker` SHALL NOT eliminate a `Candidate` from the list solely because its file size is below any threshold.
4. THE `Ranker` SHALL weight the file size bonus so that a candidate with a known large file size scores meaningfully higher than a candidate with an unknown or small file size.

---

### Requirement 4: UI-Based Ad Detection Signals

**User Story:** As a user, I want videos with ad-like UI elements (skip buttons, countdown timers) to be ranked lower, so that pre-roll ads are deprioritized relative to main content.

#### Acceptance Criteria

1. WHEN a `Candidate`'s video element is detected, THE `VideoDetector` SHALL scan the DOM near the video element for common ad UI patterns, including:
   - "Skip Ad" or "Skip" buttons (text or aria-labels containing "skip")
   - Countdown timers (text matching patterns like "5", "4", "3", "2", "1" or "Ad ends in X seconds")
   - Ad disclosure labels (text containing "Advertisement", "Ad", "Sponsored")
   - Muted-by-default state (video element has `muted` attribute set to true)
2. IF one or more ad UI patterns are detected near a `Candidate`'s video element, THE `Ranker` SHALL apply a negative Ad UI Penalty to that candidate's Confidence Score.
3. THE `Ranker` SHALL NOT remove a candidate from the list solely because ad UI patterns are detected.
4. WHEN ad UI pattern detection is unavailable or fails (e.g., DOM access denied), THE `Ranker` SHALL compute the score using only the remaining available signals without penalizing the candidate.
5. THE `VideoDetector` SHALL log detected ad UI patterns at debug level for troubleshooting purposes.

---

### Requirement 5: Playback State Signal

**User Story:** As a user, I want videos that are currently playing or visible in the viewport to be ranked higher, so that the actively-playing main content is prioritized over background or hidden ad videos.

#### Acceptance Criteria

1. WHEN a `Candidate` is detected, THE `VideoDetector` SHALL attempt to determine if the video element is currently playing or visible in the viewport.
2. IF a `Candidate`'s video element is currently playing (e.g., `HTMLVideoElement.paused === false`), THE `Ranker` SHALL apply a significant positive bonus to that candidate's Confidence Score.
3. IF a `Candidate`'s video element is visible in the viewport (e.g., not hidden by CSS, not off-screen), THE `Ranker` SHALL apply a moderate positive bonus to that candidate's Confidence Score.
4. IF a `Candidate`'s video element is hidden or off-screen, THE `Ranker` SHALL apply a small negative penalty to that candidate's Confidence Score.
5. WHEN playback state information is unavailable (e.g., video element not accessible), THE `Ranker` SHALL compute the score using only the remaining available signals without penalizing the candidate.

---

### Requirement 6: Composite Confidence Scoring

**User Story:** As a developer, I want the ranking to combine multiple weak signals into a single score, so that no single signal can incorrectly eliminate the main content video.

#### Acceptance Criteria

1. THE `Ranker` SHALL compute a Confidence Score for each `Candidate` by summing contributions from: URL type (PH get_media, direct video extension, stream manifest), temporal index bonus, file size bonus, playback state bonus/penalty, ad-pattern penalty, ad UI penalty, and tiny-file penalty.
2. THE `Ranker` SHALL produce a deterministic score for any given set of candidate attributes, so that the ranked order is stable across repeated calls with the same input.
3. WHEN two candidates have equal Confidence Scores, THE `Ranker` SHALL break the tie by preferring the candidate with the higher temporal index (later-detected).
4. THE `Ranker` SHALL be a pure function of its inputs (URL, mimeType, fileSize, detectionIndex, thumbnailUrl, title, isPlaying, isVisible, hasAdUIPatterns), containing no side effects or I/O.

---

### Requirement 7: Quality Sheet Displays Top-Ranked Candidate

**User Story:** As a user, I want the quality sheet to show me the main content video as the default choice, so I can download it immediately without confusion.

#### Acceptance Criteria

1. WHEN the user taps the download FAB and at least one `Candidate` has been detected, THE `Quality Sheet` SHALL display the Top Candidate (highest Confidence Score) prominently as the default selection.
2. THE `Quality Sheet` SHALL show the Top Candidate's title, thumbnail (if available), and available quality options.
3. THE `Quality Sheet` SHALL allow the user to tap a "Show More" or "Other Videos" option to see the ranked list of other detected candidates if they want to manually select a different video.
4. WHEN the user expands the "Other Videos" section, THE `Quality Sheet` SHALL display all remaining candidates ranked by descending Confidence Score, with visual indicators (e.g., "small file", "ad pattern detected") to help the user understand why they ranked lower.
5. WHEN only one `Candidate` has been detected, THE `Quality Sheet` SHALL display that single candidate without showing additional options.

---

### Requirement 8: Robustness — Never Accidentally Filter Out the Main Video

**User Story:** As a user, I want the app to reliably rank the main content video as the top choice, so that I can download it without having to manually search through other candidates.

#### Acceptance Criteria

1. THE `Ranker` SHALL NOT apply any hard filter that unconditionally removes a candidate based on URL pattern, file size, or detection order.
2. THE `Ranker` SHALL use only scoring penalties (negative adjustments), never elimination, so that even a candidate with a low score remains in the ranked list and can be selected if needed.
3. WHEN a candidate matches an ad-network URL pattern, THE `Ranker` SHALL apply a negative Ad Penalty, but the candidate SHALL remain ranked and selectable.
4. WHEN a candidate's file size is below 500 KB, THE `Ranker` SHALL apply a negative tiny-file penalty, but the candidate SHALL remain ranked and selectable.
5. THE `Ranker` SHALL weight its scoring signals so that the main content video (typically: large file, detected later, no ad patterns) scores significantly higher than pre-roll ads (typically: small file, detected early, ad patterns present).
6. IF the top-ranked candidate fails to fetch quality options, THE `BrowserViewModel` SHALL attempt to fetch quality options for the next-ranked candidate, and so on, ensuring the user can always download from the highest-scoring candidate that has valid options.

---

### Requirement 9: Scoring Transparency (Debug Logging)

**User Story:** As a developer, I want the scoring decisions to be logged at debug level, so that I can diagnose ranking mistakes during development and testing.

#### Acceptance Criteria

1. WHEN the `Ranker` computes a Confidence Score for a `Candidate`, THE `BrowserViewModel` SHALL emit a debug log entry containing: the candidate URL (truncated to 100 characters), the total Confidence Score, and the individual signal contributions (temporal bonus, size bonus, ad penalty, type score).
2. THE debug log entries SHALL be emitted at Android `Log.d` level and SHALL NOT appear in release builds unless debug logging is explicitly enabled.
3. THE debug log entries SHALL NOT contain personally identifiable information or full untruncated URLs that could expose user browsing history in crash reports.
