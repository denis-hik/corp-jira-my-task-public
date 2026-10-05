# Corp Jira - My task

**English** | [Русский](README.ru.md)

A native WebStorm plugin for corporate Jira Server / Data Center.

## Preview

<a href="docs/images/CorpJiraPreview.png"><img src="docs/images/CorpJiraPreview.png" alt="Corp Jira - My task preview" width="420"></a>

## Features

- Issue lists powered by JQL, toggleable filters and saved queries with custom names.
- Issue details, status transitions and assignee changes.
- Media, comments, attachments and user mentions.
- Git Log links for commits and issue drafts in AI Assistant.
- PAT sign-in, an account profile and saved approval for a specific TLS certificate.
- English and Russian UI: defaults to the IDE language and can be changed in General settings. Restart the IDE after changing the language.
- Clickable named Jira links in issue descriptions.

## Installation

Download the ZIP from the [latest release](https://github.com/denis-hik/corp-jira-my-task-public/releases/latest). In WebStorm, open Settings → Plugins → the gear menu → Install Plugin from Disk and select the ZIP. Restart the IDE if prompted.

Supported WebStorm versions: 2025.3, 2026.1 and 2026.2 (builds 253–262.*). Enter your Jira URL and PAT in the sign-in dialog. The token is stored using the IDE Password Safe; no tokens are included in this repository.

## Build

Requires Python 3 and a WebStorm installation with JBR javac and SDK libraries. The default path is `/Applications/WebStorm.app/Contents`; set `WEBSTORM_HOME` to use another location. For releases supporting 2025.3, use the WebStorm 2025.3 SDK and check the resulting ZIP against every supported IDE series with JetBrains Plugin Verifier.

```sh
python3 build.py
```

The archive is created in `dist/`. The build obfuscates internal classes and private members using ASM from the WebStorm SDK. Source code, debug metadata and the mapping file are not included in the ZIP. Keep `build/obfuscation-map.tsv` separately for each release; it is not intended for distribution.

The public plugin ID is `local.corp.jira.myTasks`. When migrating from an earlier private build, disable that build and configure your connection again in the public version.

## Limitations

Supports Jira Server / Data Center with PAT authentication; Jira Cloud compatibility is not claimed. Multi-step transitions use a built-in status map and may not match your server's workflow. New installations default to Standard mode, which only offers transitions returned by Jira for the current issue.

The AI integration uses internal JetBrains APIs.

## License and policies

[MIT](LICENSE): use, modification and redistribution are permitted with the copyright notice preserved.

- [Privacy](PRIVACY.md)
- [Security](SECURITY.md)
- [Feedback and contributions](CONTRIBUTING.md)
- [Public release policy](PUBLICATION.md)

This plugin is not affiliated with Atlassian or JetBrains and is not an official product of either company.
