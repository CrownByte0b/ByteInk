// Only the original Bionic test reference includes this portability shim.
#include <stdint.h>
#include <string.h>
#define GET_FLOAT_WORD(word, value) do { float v = (value); memcpy(&(word), &v, sizeof(v)); } while (0)
#define SET_FLOAT_WORD(value, word) do { uint32_t w = (word); memcpy(&(value), &w, sizeof(w)); } while (0)
