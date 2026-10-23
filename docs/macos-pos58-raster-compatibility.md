# Experimental Mac POS-58 raster document mode

This experimental profile is disabled by default and is not part of the official
1.3.2 release. Local test adoption requires explicit authorization. It does not establish the exact
cause of intermittent RAW corruption or repair the original Intel Mac.

On the diagnosed Mac, a synthetic receipt with two identical store-name images
printed both names as garbage over RAW. The same composed image printed both
names normally through the installed POS-58 document driver. This motivates a
scoped document path rather than a global RAW rewrite or an assumed timing fix.

## Scope and activation contract

A build can opt one exact local CUPS queue in with the JVM property
`tea.print.macPos58RasterQueue=<queue-name>`. Leave it unset to retain existing
behavior. Do not edit a running installation merely because this file exists;
packaging, app replacement and activation require separate authorization.
The macOS launcher forwards only this opt-in property from its jpackage JVM to
the child JVM that performs printing. Setting the parent property without this
forwarding does not activate the compatibility path.
For a confirmed manual-tear printer without a cutter, additionally set
`tea.print.macPos58ManualTear=true`. The launcher forwards this companion only
when the queue opt-in is present. It disables the driver's cut action and its
four extra line feeds for converted receipts; default cut intent is unchanged
when this flag is absent. The fingerprinted driver still adds its fixed
255-dot page-end feed (about 32mm); PDF margin changes do not remove it.

The selected queue must use the validated 136pt `rastertosnailep-pos` PPD at 203 dpi.
The filter binary must have SHA256
`5d48e87ecb5428e8d6f8224df3c82cbcd5ad62f5bb9f8809be7bf2e899576ca6`.
Other driver builds fail before submission, even if the filter name matches.
Only the project's raster-only prefix and supported commands are converted:
one initial ESC @, left alignment, GS v 0 mode 0 at 384 dots, ESC J feeds,
and an optional final GS V B 0 partial cut. The adapter bounds input size,
block count and cumulative height. It rejects unknown commands, truncation and
black pixels in the PPD's clipped rightmost column before submitting anything.
This is not a general ESC/POS converter. Native text and other queues keep the
original RAW submission path.

The generated monochrome PDF preserves the composed dot layout at 203 dpi,
uses the validated 136pt page width and bounded custom height, and is submitted
once through the local CUPS queue without `-o raw`. The final partial-cut intent
maps to the driver's document partial-cut option. There is no automatic RAW
fallback or retry after a submitter starts; existing unknown-result and replay
reconciliation handling remains in force. Submission success is not physical
acceptance.

## Verification and remaining limits

Focused tests cover PDF pixel reconstruction, feed spacing, clipping and size
guards, malformed input, default/native RAW preservation, one-copy document
submission, temporary-file cleanup and no fallback after a failed submission.
The source prototype is checked against the same synthetic receipt using
offline conversion through the actual installed driver. No business order is
replayed by these checks.

The fingerprinted filter trims blank space and omits its last nonblank raster
row. This profile repeats that row once in existing trailing whitespace, so
the filter consumes the duplicate and retains the original content. Offline
conversion of both the synthetic sample and the selected faulty receipt
preserved every intended black dot with no extra output dots after uniform
position normalization. Brightness and halftoning are neutralized per job.
These comparisons do not prove physical receive bytes. On the current ARM64 Mac,
the installed local build subsequently passed synthetic receipt, business-page
table-switch and manual-tear footer acceptance. The original Intel Mac and
long-term reliability remain unverified. Reverting the source changes or leaving the JVM
property unset retains the existing runtime behavior. Local app replacement is
a separate adoption step; it need not change the queue, USB topology, CUPS log
level or current durable replay state. Never restore stale replay state when
rolling an app back.

The immutable accepted local app, frozen source, offline rebuild tools and checksums
are tracked under `deliveries/`; see its README for scope and extraction instructions.
To test the current checkout (including launcher property forwarding) against the
extracted delivery's verified runtime and dependencies, run:

```sh
python3 scripts/macos/test-local.py --delivery /path/to/TeaPrintHelper-XP58-local-20261021 --output /path/to/new-test-evidence
```

This runs all application and launcher tests in an isolated working directory.
It does not start the helper or print. Standard Gradle installer builds and
Developer ID signing/notarization are separate, unverified release gates.
