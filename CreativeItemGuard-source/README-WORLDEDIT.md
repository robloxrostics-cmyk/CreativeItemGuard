# CreativeItemGuard 2.1.0 — WorldEdit protection

- Adds WorldEdit 7.4.1 API as a provided dependency.
- Intercepts WorldEdit EditSession changes at BEFORE_CHANGE and BEFORE_HISTORY.
- Applies the existing BLACKLIST/WHITELIST list to blocks placed through WorldEdit.
- Also blocks shulker boxes when `containers.block-shulkers: true`.
- A player with `creativeitemguard.bypass` is exempt.
- `creative-only: true` keeps the restriction limited to players in Creative.
- `worldedit.enabled: true` toggles the integration.

Build from `CreativeItemGuard-source` using Java 21 and Maven:
`mvn clean package`

WorldEdit must be installed on the server (7.4.1 for this build).
