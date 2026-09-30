# Android float angle and magnitude arithmetic

Patch `0003-use-android-float-angle-arithmetic.patch` adapts Bionic's float `atan2`
and its float `atan` helper for Ink's two angle call sites: vector direction and
subtraction attribute interpolation. The functions are inline and hidden, including when upstream C++ tests link dynamic libraries.
Patch `0004-use-android-float-magnitude-arithmetic.patch` also adapts Bionic's float
`hypot` for Ink's vector lengths, polyline closure and subtraction attribute interpolation.
The functions are inline and hidden. Both patches apply to both desktop native builds,
without a process-wide libm override, Android runtime dependency or NDK requirement.

## Reason

Comparing 42,740 real Android notebook rows found one marker stroke whose U-turn
changed tessellation: Linux's minimum x was 0.04084 dp farther left. Inputs and
serialized brush families were identical, and byteink agreed exactly with Google's
Linux binary. Replacing only the Linux float atan2 implementation with Android's
reproduced Android's live and packed geometry exactly. The other tested float math
routines (sin, cos, acos, hypot) did not fix it. Double atan2 rounded to float also
failed: the original float argument reduction and rounding matter to the topology.

The patches preserve Android's arithmetic, disable contraction, replace private
word macros with C++20 bit_cast, namespace the functions and quiet NaNs with
float arithmetic. Ink's valid inputs are finite; NaN payload selection is not part of
the stroke geometry contract. Other trigonometric functions keep using platform libm.

The complete synthetic matrix exposed a second rounding difference in `hypotf`:
Android returns `0x3ea1e89c` for `(0.1f, 0.3f)`, while the host's correctly-rounded
implementation returns `0x3ea1e89b`. A one-ulp magnitude change affects speed-based
calligraphy, modeler distances and extruder intersections; 115 of 280 matrix cases
had different vertex counts, outlines or packed positions. Replacing only `hypotf`
with the pinned Bionic routine made every matrix geometry field exact, including
erased and transformed projections. The previous real-notebook angle investigation
did not exercise this second failure.

Magnitude adaptations preserve all finite float intermediates and scaling, using
`std::sqrt` for the original `sqrtf`. The exceptional-input branch uses float
quiet-NaN arithmetic in place of Bionic's long-double expression; NaN payload
selection is outside the valid stroke geometry contract.

## Source and updating

Adapted from [Android Bionic android-15.0.0_r1](https://android.googlesource.com/platform/bionic/+/361ba86734fb2821a6adcfdf775db8abd04e0de0/libm/upstream-freebsd/lib/msun/src/),
commit `361ba86734fb2821a6adcfdf775db8abd04e0de0`:

- `s_atanf.c`: SHA-256 `c5d396befa5c58581f4e43b57e1b39373447d4416901fe936b0fa1cfb85066f4`
- `e_atan2f.c`: SHA-256 `c1237a88100ae4579b025092d02c1d7126fcab71d6589801beb576c4efde1f2b`
- `e_hypotf.c`: SHA-256 `512f270d3290138d44d5a2d14ae24fa573433f2cec8e88ee9fdd417a48744a73`

The Sun permission notice is preserved in the source patch and the loader jar's
`com/vivenotes/byteink/nativeloader/ANDROID-MATH-LICENSE.txt`.
This is a local compatibility patch; no upstream PR has been filed.

When updating the Ink/Android pins, rerun the native math/vector tests, the strict Google
Linux baseline oracle, the committed Android matrix on both desktop OSes and the Android
notebook comparison. `native/build-oracle-baseline.sh` creates an unshipped validation
library at the same Ink pin with only packaging patches 0001/0002; its angle and magnitude
profiles are both `platform`. `oracleCompare` checks that baseline against Google with
unchanged geometry/topology rules. `oracleProduction` dumps the shipped Android-math
library for strict Linux/Windows comparison. The baseline is never packaged or published. Remove the patch
only when the new native builds reproduce Android's geometry within the existing
coordinate tolerance. Do not widen that tolerance to accommodate topology changes.
Native build.properties records both arithmetic profiles and the patch checksums
with the binary checksum.

## Verified local results

The fixed Linux library matches Android exactly for all 512,560 real-notebook
bounds/projection values. The original coordinate tolerance is unchanged.
Before patch 0004, Google Linux geometry agreed within tolerance after the angle correction;
one live AA derivative differed by 0.000143 and used the existing informational derivative
policy. The complete magnitude correction changes 209 Google Linux geometry/topology values
in the synthetic engine oracle. That platform-math output cannot certify Android geometry.
The strict independent engine/pin check now compares Google with the matching platform-math
validation library; shipped-library correctness is checked against actual Android goldens and
against the other desktop OS. No coordinate or topology tolerance is widened.

With both compatibility patches, the production Linux library reproduces every
geometry JSON field of the 280-case Android matrix exactly, with zero maximum
float gap, and all 280 software path images are pixel-identical. All 115 upstream
Linux native test targets pass. The scoped magnitude implementation also matched
NDK 28.2's independently compiled Bionic object over 250,000 deterministic
binary32 argument pairs, excluding NaN payload selection.

Actual Windows 11 verification also passes on Zulu 25, Zulu 27 and JBR 25. All 280 matrix
geometry JSONs are byte-identical to Linux and every software image is RGB-pixel identical.
The separate 16,278-value strict production cross-OS oracle has zero mismatches on all three
runtimes. All five native Windows test executables pass, including the magnitude bit regressions.
Fresh independent Windows builds with compiled action caching disabled reproduce every DLL byte.
