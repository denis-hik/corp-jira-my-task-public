# Publication policy

This repository starts from a clean source snapshot. Previous private commits, tags, releases, internal data and build mappings are not included.

Publish only reviewed source, documentation and release ZIPs with SHA-256. Never commit credentials, local IDE settings, private issue data or obfuscation maps. `.gitignore` does not remove files already tracked.

Use pull requests for main; disable force pushes and branch deletion when configuring branch protection. Configure required checks only after a working CI exists. Keep default workflow token permissions read-only. Do not expose secrets to forked pull requests. Enable private vulnerability reporting and available secret scanning.

These guidelines do not automatically configure GitHub settings. Check their actual state in repository settings.

Release builds include MIT license text. Only the IDE build series declared in plugin.xml is supported.
