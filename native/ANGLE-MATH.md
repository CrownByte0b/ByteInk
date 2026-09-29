# Android float angle arithmetic

Patch `0003-use-android-float-angle-arithmetic.patch` adapts Bionic's float `atan2`
and its float `atan` helper for Ink's two angle call sites: vector direction and
subtraction attribute interpolation. The functions are inline and hidden, including when upstream C++ tests link dynamic libraries.
There is no process-wide libm override, Android runtime dependency or NDK requirement.
The same patch applies to both desktop native builds.

## Reason

Comparing 42,740 real Android notebook rows found one marker stroke whose U-turn
changed tessellation: Linux's minimum x was 0.04084 dp farther left. Inputs and
serialized brush families were identical, and byteink agreed exactly with Google's
Linux binary. Replacing only the Linux float atan2 implementation with Android's
reproduced Android's live and packed geometry exactly. The other tested float math
routines (sin, cos, acos, hypot) did not fix it. Double atan2 rounded to float also
failed: the original float argument reduction and rounding matter to the topology.

The patch preserves Android's arithmetic, disables contraction, replaces private
word macros with C++20 bit_cast, namespaces the two functions, and handles NaNs by
float addition. Ink's valid inputs are finite; NaN payload selection is not part of
the stroke geometry contract. Other trigonometric functions keep using platform libm.

## Source and updating

Adapted from [Android Bionic android-15.0.0_r1](https://android.googlesource.com/platform/bionic/+/361ba86734fb2821a6adcfdf775db8abd04e0de0/libm/upstream-freebsd/lib/msun/src/),
commit `361ba86734fb2821a6adcfdf775db8abd04e0de0`:

- `s_atanf.c`: SHA-256 `c5d396befa5c58581f4e43b57e1b39373447d4416901fe936b0fa1cfb85066f4`
- `e_atan2f.c`: SHA-256 `c1237a88100ae4579b025092d02c1d7126fcab71d6589801beb576c4efde1f2b`

The Sun permission notice is preserved in the source patch and the loader jar's
`com/vivenotes/byteink/nativeloader/ANDROID-MATH-LICENSE.txt`.
This is a local compatibility patch; no upstream PR has been filed.

When updating the Ink/Android pins, rerun the native math/vector tests, the Google
Linux differential oracle and the Android notebook comparison. Remove the patch
only when the new native builds reproduce Android's geometry within the existing
coordinate tolerance. Do not widen that tolerance to accommodate topology changes.
Native build.properties records the patch checksum with the binary checksum.

## Verified local results

The fixed Linux library matches Android exactly for all 512,560 real-notebook
bounds/projection values. The original coordinate tolerance is unchanged.
The Google Linux differential oracle still checks topology and geometry strictly.
Its report records the two angle-math profiles; the existing cross-platform
antialiasing-derivative policy also applies across these profiles. One live AA
derivative differs by 0.000143; it is reported as informational. The path renderer
does not consume those shader derivatives.
