# Compatibility — 2.24.0

Supported WebStorm branches: 2025.3, 2026.1, 2026.2 (`253`–`262.*`). Java 21.

The release ZIP was compiled against WebStorm SDK 253.30387.83 and checked with JetBrains Plugin Verifier 1.410 on 2026-09-29.

- Plugin local.corp.jira.myTasks:2.24.0 against WS-253.30387.83: Compatible. 2 usages of deprecated API
- Plugin local.corp.jira.myTasks:2.24.0 against WS-262.10315.144: Compatible. 2 usages of deprecated API. 3 usages of internal API
- Plugin local.corp.jira.myTasks:2.24.0 against WS-261.22158.274: Compatible. 2 usages of deprecated API
- Plugin local.corp.jira.myTasks:2.24.0 against WS-253.28294.332: Compatible. 2 usages of deprecated API

No binary compatibility errors were reported. Deprecated/internal API warnings remain. Static verification does not exercise the live Jira connection or reflective AI Assistant integration; AI availability depends on the installed assistant and its version.

Recheck the built ZIP before expanding the range. Reference: https://github.com/JetBrains/intellij-plugin-verifier
