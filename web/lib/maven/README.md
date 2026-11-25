# Vendored Maven artifacts

Artifacts the web module needs that are not on Maven Central, kept here as a
local Maven repository (`web/pom.xml` declares it as `file://…/lib/maven`, with
`checksumPolicy` set to fail).

## dcm4che 5.35.2

The DICOM toolkit, for the Basic Application Level Confidentiality Profile
applied to images on export (`ExportScrubber`, DR-028 amendment 2026-11-24).

- **Source:** `https://www.dcm4che.org/maven2/org/dcm4che/`, release 5.35.2
  (15 October 2026), <https://github.com/dcm4che/dcm4che>.
- **Licence:** tri-licensed MPL 1.1 / GPL 2.0 or later / LGPL 2.1 or later; used
  here under the LGPL 2.1 or later, compatible with this project's LGPL v3.
- **Why vendored:** dcm4che publishes only to its own repository, and its parent
  POM declares further repositories (dcm4che.org, a GitHub-hosted personal
  repository for the Weasis BOM it imports, JBoss). Declaring them in the build
  made dependency resolution on GitHub's runners hang for hours and Trivy's
  POM analysis time out.
- **What is here:** the four jars, byte for byte as released (their SHA-1
  matches the `.sha1` dcm4che publishes), and flat POMs written for this
  repository: no parent, no repositories, only the runtime dependencies.

| Artifact | SHA-256 of the jar |
|---|---|
| dcm4che-core | `f660d2aa8dbcabda1d0051431d9c39ff39453de9e7541b227de8aa1a900441a8` |
| dcm4che-dict | `4308daa81c32c8b2740cef5c9cb1fc99c82437fe173ff4087f45e75c31bd7e50` |
| dcm4che-dcmr | `1f9b84de571f7181af570c53148e5fac53921a7c21217b4c828deae3ecb490f8` |
| dcm4che-deident | `3a6051f84a20fcbfc511dd3df928a1408925b825ffaea2be7460893fba757abb` |

**To update:** download the new release's jars and their `.sha1` from the
source above, check the SHA-1, copy the flat POMs to the new version
(dependencies as in the release's own POMs), regenerate the `.sha1` files,
update this table and `dcm4che.version` in the root `pom.xml`.
