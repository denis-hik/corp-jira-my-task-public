# Privacy

Corp Jira - My task connects directly to the Jira server configured by the user. It accesses the profile, issues, comments, attachments, users, statuses and transition metadata needed for its features. User actions may update issues, upload files or post comments to that server.

The PAT is stored through JetBrains Password Safe. Jira and Git host settings, JQL preferences and other options are stored using IDE settings. An explicitly accepted TLS certificate fingerprint is also stored in IDE settings. Password Safe's storage backend is controlled by the IDE.

The plugin does not implement analytics or send telemetry to the plugin author. Jira data is loaded into the IDE for display. Downloaded media and files may also be retained locally for previews or export.

The optional AI integration hands issue content to the installed JetBrains AI Assistant as a draft when requested by the user. Subsequent processing is governed by that plugin and the selected AI provider. Do not use this feature for data that your organization prohibits sharing with that provider.

Links open in the user's browser. Git commit lookup uses repositories available in the current IDE project. External sites and plugins have their own privacy policies.

Signing out removes the saved PAT; it does not revoke the token in Jira or delete previously downloaded/exported files, browser history or AI drafts.
