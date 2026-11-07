# Release docs checklist

Owner rule (2026-11-07): when a version is uploaded, **everything** about it must
match that build. No upload to any Play track, and no request to the owner to
sign, until every item below is done for the exact build being released.

`scripts/release.sh build` reads this file from the commit it builds. It
refuses unless the two version lines match that build's `versionCode` and
`versionName`, and every item is ticked. `sign` checks it again. Edits that
aren't committed don't count.

How to fill it in, for every new version:

1. Set `versionCode` and `versionName` below to the values in
   `app/build.gradle.kts`.
2. Untick everything (`[x]` → `[ ]`). Every item is redone for each version.
3. Do each item for this build, then tick it (`[ ]` → `[x]`). Don't rename the
   item keys (the word before the colon): the script looks for them.
4. Commit, then run `scripts/release.sh build <that commit>`.

versionCode: 1
versionName: 1.0

- [ ] screenshots-en: Play screenshots in English, taken fresh from this build
- [ ] screenshots-he: Play screenshots in Hebrew, taken fresh from this build
- [ ] short-description: Play short description, English and Hebrew, matches this build
- [ ] full-description: Play full description, English and Hebrew, matches this build
- [ ] release-notes: release notes ("What's new") for this version, English and Hebrew
- [ ] data-safety: Play Data safety form matches this build's permissions and data use
- [ ] website-privacy: the gh-pages site and the privacy policy match this build
- [ ] in-app-help: in-app help / About matches this build
- [ ] readme: README.md matches this build
