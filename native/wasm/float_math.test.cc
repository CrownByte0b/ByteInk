// Compare the linked Wasm acosf with the unchanged, independently compiled Bionic source.
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <initializer_list>

#define __FBSDID(value)
#define acosf ByteInkBionicReferenceAcos
#include "testdata/bionic-acosf.c"
#undef acosf

int main() {
  uint32_t count = 0;
  auto check = [&](uint32_t bits) {
    volatile float observed = std::bit_cast<float>(bits);
    const float actual = std::acos(observed);
    const float expected = ByteInkBionicReferenceAcos(observed);
    if (std::isnan(expected) ? !std::isnan(actual) :
        std::bit_cast<uint32_t>(actual) != std::bit_cast<uint32_t>(expected)) {
      std::fprintf(stderr, "acosf(%08x): %08x != Bionic %08x\n", bits,
          std::bit_cast<uint32_t>(actual), std::bit_cast<uint32_t>(expected));
      std::exit(1);
    }
    ++count;
  };
  for (uint32_t bits : {0u, 1u, 0x327fffffu, 0x32800000u, 0x32800001u,
      0x3effffffu, 0x3f000000u, 0x3f000001u, 0x3f7fffffu, 0x3f800000u,
      0x3f800001u, 0x7f800000u, 0x7fc00000u}) {
    check(bits); check(bits | 0x80000000u);
  }
  uint32_t state = 0x42595445u;
  for (int i = 0; i < 250000; ++i) {
    state ^= state << 13; state ^= state >> 17; state ^= state << 5;
    check(state % 0x3f800001u | (state & 0x80000000u));
  }
  std::printf("BIONIC_ACOS_WASM_PASS %u comparisons (finite results bit-exact)\n", count);
}
