# Support

Need help with Paperless Scanner? Here's how to get support.

## 📖 Documentation

Start with our comprehensive documentation:

- **[README](../README.md)** - Overview, features, installation
- **[FAQ](../README.md#-faq)** - Frequently asked questions
- **[Troubleshooting](../README.md#-troubleshooting)** - Common issues and solutions
- **[Technical Documentation](TECHNICAL.md)** - In-depth technical details
- **[API Reference](API_REFERENCE.md)** - Paperless-ngx API documentation

## 🐛 Bug Reports

Found a bug? [Open a bug report](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=bug_report.md)

**Before reporting:**
- ✅ Check [existing issues](https://github.com/napoleonmm83/paperless-scanner/issues)
- ✅ Verify you're using the [latest version](https://github.com/napoleonmm83/paperless-scanner/releases)
- ✅ Review the [Troubleshooting guide](../README.md#-troubleshooting)
- ✅ Make sure it's not a Paperless-ngx server issue

**What to include:**
- Device model and Android version
- App version
- Paperless-ngx version
- Steps to reproduce
- Expected vs actual behavior
- Screenshots or screen recordings
- Error messages or logs

## 💡 Feature Requests

Have an idea? [Suggest a feature](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=feature_request.md)

**Before suggesting:**
- ✅ Check [existing feature requests](https://github.com/napoleonmm83/paperless-scanner/issues?q=is%3Aissue+label%3Aenhancement)
- ✅ Review the [Roadmap](ROADMAP.md) for planned features
- ✅ Consider if it benefits the wider community

**What to include:**
- Clear description of the feature
- Problem it solves
- Proposed solution
- Example use cases

## ❓ Questions

Have a question? [Ask here](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=question.md)

**Before asking:**
- ✅ Check the [FAQ](../README.md#-faq)
- ✅ Search [existing questions](https://github.com/napoleonmm83/paperless-scanner/issues?q=label%3Aquestion)
- ✅ Review the [documentation](.)

## 💬 Community Support

Join the community for discussions and help:

- **[r/paperlessngx](https://reddit.com/r/paperlessngx)** - Paperless-ngx community
- **[r/paperless](https://reddit.com/r/paperless)** - General Paperless community
- **[r/selfhosted](https://reddit.com/r/selfhosted)** - Self-hosting discussions

## ⏱️ Response Times

This is an open-source project maintained by volunteers:

- **Bug reports:** We try to respond within 48-72 hours
- **Feature requests:** May take longer to review and prioritize
- **Questions:** Community members often respond faster than maintainers

**Premium subscribers:** While we don't offer priority support yet, we're working on it!

## 🔒 Security Issues

**DO NOT** report security vulnerabilities as public issues!

For security issues, please contact the maintainers directly:
- [Contact via GitHub](https://github.com/napoleonmm83)

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

We'll acknowledge receipt within 48 hours and work with you to resolve it.

## 📧 Email Support (Not Available Yet)

We're considering setting up dedicated email support. Stay tuned!

## 🎯 What We Can Help With

### ✅ We can help with:

- App bugs and crashes
- Feature clarifications
- Setup and configuration
- Best practices
- Understanding error messages
- Integration issues

### ❌ We cannot help with:

- Paperless-ngx server issues → [Paperless-ngx support](https://github.com/paperless-ngx/paperless-ngx)
- Android OS issues → [Android support](https://support.google.com/android)
- Device-specific problems → Your device manufacturer
- Custom modifications to the app
- Legal or licensing questions

## 🛠️ Self-Help Resources

### Quick Diagnostics

**App won't connect to server:**
```bash
# Test connection from browser
https://your-paperless-server.com/api/

# Should return JSON with API version
```

**Scanner not working:**
- Check Google Play Services is installed and updated
- Device must have Android 8.0+ (API 26)
- Camera permissions must be granted

**Upload failing:**
- Check internet connection
- Verify you're logged in (token may have expired)
- Check Paperless-ngx server logs
- Try a small test document first

**AI suggestions not working (Premium):**
- Verify subscription is active (Settings → Subscription)
- Check internet connection (AI requires online access)
- Check if fair use limit exceeded

### Logs and Debugging

**Enable verbose logging:**
1. Settings → About → Tap version 7 times
2. Developer options will appear
3. Enable "Verbose Logging"

**View logs:**
```bash
# Via ADB (requires USB debugging)
adb logcat | grep "Paperless"
```

**Clear app data:**
```
Settings → Apps → Paperless Scanner → Storage → Clear Data
```

⚠️ **Warning:** This will log you out and reset all settings!

## 📊 Reporting Crashes

If the app crashes:

1. **Via Google Play:** Crash reports are automatically sent (opt-in during setup)
2. **Via GitHub:** [Open a bug report](https://github.com/napoleonmm83/paperless-scanner/issues/new?template=bug_report.md) with:
   - What you were doing when it crashed
   - How often it happens
   - Device info

## 🤝 Contributing

Want to help improve the app? See [CONTRIBUTING.md](../CONTRIBUTING.md) for:

- How to report bugs effectively
- How to suggest features
- How to contribute code
- Development setup guide
- Coding standards

## 🙏 Thank You

Thank you for using Paperless Scanner! Your feedback and support help make this app better for everyone.

---

**Happy Scanning! 📱✨**
