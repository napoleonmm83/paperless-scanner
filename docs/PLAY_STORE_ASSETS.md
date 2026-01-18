# Play Store Assets

This document tracks the status of Play Store marketing assets.

## Screenshots

### Current Status

**Required:** 8 phone screenshots (minimum 2, recommended 8)
**Current:** 5 screenshots available

### Existing Screenshots

Located in: `fastlane/metadata/android/{locale}/images/phoneScreenshots/`

| # | Filename | Description | Status |
|---|----------|-------------|--------|
| 1 | 1_login.png | Login screen with server connection | ✅ Ready |
| 2 | 2_scan.png | MLKit scanner in action | ✅ Ready |
| 3 | 3_upload.png | Upload screen with metadata entry | ✅ Ready |
| 4 | 4_multipage.png | Multi-page document management | ✅ Ready |
| 5 | 5_batch.png | Batch import functionality | ✅ Ready |

### Missing Screenshots (Need 3 more for optimal listing)

| # | Description | Priority | Notes |
|---|-------------|----------|-------|
| 6 | **Settings & App-Lock** | High | Show security features (App-Lock toggle, biometric, timeout) |
| 7 | **AI Suggestions (Premium)** | High | Show AI tag suggestions in action on upload screen |
| 8 | **Dark Mode** | Medium | Same screen as #3 but in dark mode to show theme support |

### Screenshot Requirements

**Technical Specs:**
- Format: PNG or JPEG
- Min dimensions: 320px
- Max dimensions: 3840px
- Aspect ratio: 16:9 or 9:16 (portrait recommended)
- Max file size: 8MB per screenshot
- Language: Should match locale (en-US, de-DE, etc.)

**Content Guidelines:**
- No transparency
- No rounded corners (Play Store adds them)
- Real content (no Lorem Ipsum)
- Show app in use (not just static screens)
- Highlight key features
- Include status bar
- Portrait orientation recommended

### Screenshot Order & Messaging

Recommended order for Play Store display:

1. **Hero Shot** - Upload screen with tags (current #3)
   - Message: "Upload documents with smart tagging"

2. **Scan Process** - MLKit scanner (current #2)
   - Message: "Smart document scanning with edge detection"

3. **AI Suggestions** - Premium feature (MISSING - need to create)
   - Message: "AI-powered tag suggestions save you time"

4. **Multi-Page** - Document management (current #4)
   - Message: "Combine multiple pages into one PDF"

5. **Batch Import** - Gallery import (current #5)
   - Message: "Import multiple documents at once"

6. **Security** - Settings with App-Lock (MISSING - need to create)
   - Message: "Protect your documents with App-Lock"

7. **Dark Mode** - Upload screen dark (MISSING - need to create)
   - Message: "Beautiful design, day or night"

8. **Login** - Server connection (current #1)
   - Message: "Connect to your Paperless-ngx server"

**Note:** Screenshot order in filesystem doesn't matter - Play Store lets you reorder them in the console.

---

## App Icon

**Current Status:** ✅ Ready

Located in: `app/src/main/res/mipmap-*/`

**Requirements:**
- 512x512 PNG for Play Store (high-res icon)
- No transparency
- Safe zone: Keep important elements in center 80%
- Should work at small sizes (48x48dp on device)

---

## Feature Graphic (Optional but Recommended)

**Current Status:** ❌ Not created

**Specs:**
- Dimensions: 1024x500px (exactly)
- Format: PNG or JPEG
- Max file size: 1MB
- Used in: Play Store listing header

**Content Ideas:**
- App name + tagline
- Key feature icons
- Screenshot montage
- Brand colors: Match app theme

---

## Promotional Video (Optional)

**Current Status:** ❌ Not created

**Specs:**
- Length: 30 seconds to 2 minutes (recommended: 30-60s)
- Format: YouTube video (public or unlisted)
- Just paste YouTube URL in Play Console

**Content Ideas:**
- Quick workflow demo: Scan → Tag → Upload
- Highlight AI features
- Show real-world use case
- End with call-to-action

---

## Localized Assets

### Required Locales

- **en-US** (English - Primary)
- **de-DE** (German - Primary target market)

### Optional Locales (Future)

Based on Paperless-ngx community:
- **fr-FR** (French)
- **es-ES** (Spanish)
- **it-IT** (Italian)
- **nl-NL** (Dutch)
- **pl-PL** (Polish)

**Note:** Screenshots can be the same (if English UI), but descriptions must be translated.

---

## Testing Checklist

Before uploading to Play Store:

- [ ] All screenshots are high-quality (min 1080p)
- [ ] Screenshots show real content, not placeholder data
- [ ] Text in screenshots is readable on mobile
- [ ] Screenshots highlight key features
- [ ] Dark mode screenshot shows app looks good
- [ ] No sensitive data visible (credentials, tokens, real documents)
- [ ] Consistent aspect ratio across all screenshots
- [ ] File sizes under 8MB
- [ ] Filenames follow convention (1_login.png, 2_scan.png, etc.)

---

## How to Create Missing Screenshots

### Using Android Studio

1. **Run app on emulator** (Pixel 6 Pro, API 33+)
2. **Navigate to the screen** you want to capture
3. **Take screenshot:**
   - Use emulator camera button
   - Or: `Ctrl+S` / `Cmd+S`
   - Or: ADB command: `adb exec-out screencap -p > screenshot.png`
4. **Save as PNG** with appropriate filename
5. **Optimize file size** (optional): Use pngquant or ImageOptim

### Content for Missing Screenshots

**Screenshot #6: Settings & App-Lock**
- Open Settings screen
- Show Security section with:
  - App-Lock toggle enabled
  - Biometric unlock enabled
  - Timeout set to 5 minutes
  - "Change Password" option visible

**Screenshot #7: AI Suggestions (Premium)**
- Upload screen after scanning a document
- Show AI suggestion chip/banner with:
  - Suggested tags
  - Suggested title
  - "Apply" and "Dismiss" buttons
  - Premium badge/indicator

**Screenshot #8: Dark Mode**
- Same as screenshot #3 (Upload screen)
- But with dark theme active
- Shows Material 3 dark theme colors
- Make sure all text is readable

---

## Current Fastlane Metadata

### Titles (50 char limit)

✅ **en-US:** "Paperless Scanner" (17 chars)
✅ **de-DE:** "Paperless Scanner" (17 chars)

### Short Descriptions (80 char limit)

✅ **en-US:** "Native Paperless-ngx scanner with AI-powered tag suggestions. Fast & private." (77 chars)
✅ **de-DE:** "Nativer Paperless-ngx Scanner mit KI-Vorschlägen. Schnell & privat." (68 chars)

### Full Descriptions (4000 char limit)

✅ **en-US:** ~2050 chars (optimized)
✅ **de-DE:** ~2150 chars (optimized)

---

## Next Steps

1. **Create missing screenshots** (3 screenshots)
   - Settings & App-Lock
   - AI Suggestions (Premium)
   - Dark Mode variant

2. **Create feature graphic** (1024x500px)
   - Optional but highly recommended
   - Improves Play Store listing appearance

3. **Add screenshot text overlays** (optional)
   - Use Play Store's text overlay feature
   - Add short captions to each screenshot
   - Highlights key features

4. **Upload to Play Store Console**
   - Graphics & Screenshots section
   - Upload all 8 screenshots
   - Reorder to match recommended sequence
   - Add feature graphic
   - Review preview on different devices

5. **A/B test descriptions** (after launch)
   - Play Store supports A/B testing
   - Test different short descriptions
   - Test different screenshot orders
   - Optimize for conversion

---

**Last Updated:** 2026-01-18
**Status:** Descriptions ready, 5/8 screenshots ready, feature graphic needed
