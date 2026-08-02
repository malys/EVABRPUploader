# Pull Request

**Type of Change**
- [ ] Bug fix (non-breaking, fixes issue #_)
- [ ] Feature (non-breaking, adds functionality)
- [ ] Breaking change (fix or feature that changes existing functionality)
- [ ] Documentation update

**Related Issue**
Closes #(issue)

## Description

Please include a summary of the changes and the motivation behind them:

- What problem does this solve?
- How was this tested?
- Are there any breaking changes?

## Testing

Describe how you tested your changes (local device, emulator, specific scenarios):

- [ ] Tested on emulator (AAOS 9, MT2712)
- [ ] Tested on physical device (firmware version: _)
- [ ] Manual test steps: _
- [ ] Unit/integration tests added/updated

**ABRP Integration Testing (if applicable)**
- [ ] No ABRP impact (UI/internal logic only)
- [ ] ABRP API responses validated
- [ ] Network error handling tested (offline → online transitions)
- [ ] Real-time metric upload verified
- [ ] Payload structure validated against ABRP schema

## Code Review Checklist

- [ ] Code follows project style and conventions
- [ ] No new permissions added without justification
- [ ] No hardcoded secrets, URLs, or credentials
- [ ] No API tokens logged to logcat
- [ ] Comments explain complex logic
- [ ] Breaking changes documented in commit message
- [ ] Related documentation (README, FIRMWARE.md) updated

**Security Considerations**
- [ ] No prompt injection vulnerabilities (input validated)
- [ ] ABRP credentials stored securely (encrypted SharedPreferences)
- [ ] HTTPS enforced on all API endpoints
- [ ] No certificate pinning weaknesses introduced
- [ ] Dependencies checked for known vulnerabilities

**Stability Considerations**
- [ ] No crashes observed during testing
- [ ] No ANR (Application Not Responding) warnings
- [ ] Network timeouts handled gracefully (retries, backoff)
- [ ] Battery impact measured (background service overhead)
- [ ] Memory usage reasonable (no unbounded buffers)

## CI/CD Status

Ensure all checks pass:
- [ ] Tests pass locally (`./gradlew test`)
- [ ] Permission gate passes (`mise run check`)
- [ ] No new lint errors (`./gradlew lint`)
- [ ] APK builds without warnings (`./gradlew build`)
- [ ] Security checks pass (gitleaks, mobsfscan, dependency-check)

## Claude-Assisted Description (Optional)

*If you used Claude AI to refine this PR description, design, or commit messages, summarize how it was improved:*
- Original issue: _
- Claude suggestions applied: _
- Confidence in description clarity: high / medium / low

---

**Note:** All contributions are subject to [CONTRIBUTING.md](CONTRIBUTING.md). Please ensure your PR aligns with security and stability requirements for real-time vehicle telemetry systems.
- [ ] New behaviour is covered by a unit test
- [ ] Tried on a real MG4 — if yes, which firmware: <!-- e.g. SWI133 -->

## Vehicle-safety checklist

- [ ] This change does not write anything to the vehicle
- [ ] Unreadable properties are omitted from telemetry, not defaulted to a value
- [ ] Any new failure mode fails closed
- [ ] No new `uses-permission` — or it is added to the allowlist with a justification
- [ ] No credential reaches a URL, a log line or a crash report

## Notes for the reviewer

<!-- Anything you are unsure about, or deliberately left out of scope. -->
