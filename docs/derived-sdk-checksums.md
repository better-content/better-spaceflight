# Locally generated Forge SDK checksum

The strict verification entry for `net.minecraftforge:forge:1.20.1-47.4.22_mapped_official_1.20.1` retains its original checksum and admits one specifically reviewed cached JAR:

- Original recorded SHA-256: `57930057043b9da110644d454c8efc3a247cb213bb310a1e2a46dbac2743f194`.
- Reviewed cached SHA-256: `dfad87a5326666b20d4282bc5a8f0fbf92870fb00d494ee1583113c95c7c1c3e`.

On 2026-11-03, a separate dependency-only project regenerated the mapped SDK in a fresh Forge user repository using ForgeGradle6.0.54, Java17, official1.20.1 mappings and Forge47.4.22. No mod source or native game was executed. Existing cached upstream/intermediate SDK inputs were reused, not source-owned mod outputs. The regenerated JAR had SHA-256 `d4ca6fa1c730fcafba58aca6d492d3061588a830a21e298822721a8e637f5016`; all **9,723 entry names and uncompressed entry SHA-256 values** exactly matched the reviewed cached JAR. Their Forge input receipts were byte-identical; 9,235 ZIP entry timestamps differed. The regenerated timestamp-specific hash is not automatically admitted.

The sorted, compact JSON entry-name→SHA-256 manifest has SHA-256 `d782bfc3cf63528ad94859e2deed97274773ef48bfdfd54300ec530ebf7f8083`. The raw Forge userdev and MCP configuration archive SHA-1 values were separately matched against their HTTPS publisher checksum endpoints:

- Forge47.4.22 userdev: `d5f3a9eca7991e8863070df9c6daa25dd9314a71`.
- MCP1.20.1-20230612.114412: `c7d29380ddb38becad7c0819b5b325e43bca23f0`.

Evidence is retained in the consolidation operation's `upstream-sdk-forge-r7/` directory, including generation logs, input receipts, publisher checksums and per-entry hashes. Strict verification stays enabled. No group-wide trust rule, version change, verification-off option or acceptance of unknown future bytes is introduced. A differently generated JAR still requires review; a dependency-only derivation is not a mod build or runtime qualification.
