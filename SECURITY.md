# Security

Do not include Jira tokens, internal URLs, issue contents, attachments, personal data, or IDE logs containing those values in public issues or pull requests.

Report suspected vulnerabilities privately through GitHub's “Report a vulnerability” action when available. If private reporting is not available, open an issue requesting a private contact channel without technical details or sensitive data. Do not publish exploit details before coordinating a fix.

Use a Jira PAT with only the permissions needed for your work. Credentials are stored through the IDE Password Safe. Signing out removes the saved PAT for that Jira host; revoking a token must be done in Jira.

TLS verification is enabled by default. A manually accepted certificate is remembered for the configured origin and certificate fingerprint. Prefer a valid certificate issued by your organization's trusted CA.

Only the latest release receives fixes. Compatibility is limited to the IDE versions declared in the plugin manifest.
