# Branch consolidation — 4 November 2026

All GitHub branch tips and the local archived beta branch were reviewed against
main. There were no open pull requests at the start of this pass.

The current native course/provider implementation is retained. History merges
record these already-covered or retired branches without reverting newer code:

- Preserved Inbar recovery: main already contains the profile revision checks,
  verified-session reuse, refresh merging and recovery tests. Its later SMS
  timeout fix is retained.
- Phone build / verification methods: Lemida PDF reads and Downloads, SMS method
  selection and the delayed Inbar SMS fix are already integrated. The historical
  Motion compatibility workaround removes icons/replaces current library APIs;
  it is superseded by the current Motion version. Historical phone-build receipts
  remain in commit history rather than replacing current documentation.
- The alternate Lemida SMS commit is patch-equivalent to the integrated change.
- The local 2025 beta backend prototype requires an extra Scholix account and
  sends platform credentials to a server. It is retired in favor of the current
  native on-device providers, not reactivated by consolidation.

The resulting application tree is identical to the previously built and tested
350af28 implementation. Source edits and the release artifact in the original
checkout remain untouched. Obsolete branches are pruned only after their tips
are reachable from the pushed main branch; active main is retained.
