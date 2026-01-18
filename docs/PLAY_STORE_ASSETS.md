# Play Store Assets

This document tracks the status of Play Store marketing assets.

## Screenshots

### Current Status

**Required:** 8 phone screenshots (minimum 2, recommended 8)
**Current:** ✅ 8 screenshots available (COMPLETE!)

### Existing Screenshots

Located in: `fastlane/metadata/android/{locale}/images/phoneScreenshots/`

| # | Filename | Description | Size | Status |
|---|----------|-------------|------|--------|
| 1 | 1_hero_upload.png | Upload/Tagging screen (Hero Shot) | 151K | ✅ Ready |
| 2 | 2_scan.png | MLKit scanner interface | 76K | ✅ Ready |
| 3 | 3_ai_suggestions.png | AI tag suggestions (Premium feature) | 174K | ✅ Ready |
| 4 | 4_documents_list.png | Documents overview/list | 173K | ✅ Ready |
| 5 | 5_settings_applock.png | Settings with App-Lock security | 172K | ✅ Ready |
| 6 | 6_home.png | Home dashboard | 176K | ✅ Ready |
| 7 | 7_scan_result.png | Scanned document result | 244K | ✅ Ready |
| 8 | 8_login.png | Server login/connection | 85K | ✅ Ready |

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

✅ **Optimized for Play Store (current order):**

1. **1_hero_upload.png** - Upload/Tagging screen
   - Message: "Upload documents with smart tagging"

2. **2_scan.png** - MLKit scanner interface
   - Message: "Smart document scanning with edge detection"

3. **3_ai_suggestions.png** - AI tag suggestions (Premium)
   - Message: "AI-powered tag suggestions save you time"

4. **4_documents_list.png** - Documents overview
   - Message: "Manage all your documents in one place"

5. **5_settings_applock.png** - Settings with App-Lock
   - Message: "Protect your documents with App-Lock"

6. **6_home.png** - Home dashboard
   - Message: "Your document workflow at a glance"

7. **7_scan_result.png** - Scanned document result
   - Message: "Perfect scan quality every time"

8. **8_login.png** - Server connection
   - Message: "Connect to your Paperless-ngx server"

**Note:** Screenshot order in filesystem DOES matter for fastlane automated upload. Files are uploaded in alphabetical order (1-8).

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

1. ✅ ~~Create missing screenshots~~ **COMPLETE!** All 8 screenshots ready

2. **Create feature graphic** (1024x500px) - OPTIONAL
   - Improves Play Store listing appearance
   - Not required but recommended

3. **Add screenshot text overlays** (optional)
   - Use Play Store's text overlay feature
   - Add short captions to each screenshot
   - Highlights key features

4. **Upload to Play Store Console** - READY!
   - Graphics & Screenshots section
   - Upload all 8 screenshots (via fastlane or manually)
   - Screenshots will be uploaded in order 1-8
   - Add feature graphic (if created)
   - Review preview on different devices

5. **A/B test descriptions** (after launch)
   - Play Store supports A/B testing
   - Test different short descriptions
   - Test different screenshot orders
   - Optimize for conversion

---

**Last Updated:** 2026-01-18
**Status:** ✅ **READY FOR PLAY STORE!** All 8 screenshots complete, descriptions optimized (DE + EN), feature graphic optional
