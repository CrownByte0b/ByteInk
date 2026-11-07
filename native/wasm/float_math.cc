/* e_acosf.c -- float version of e_acos.c.
 * Conversion to float by Ian Lance Taylor, Cygnus Support, ian@cygnus.com.
 */

/*
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunPro, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */

// Adapted from Bionic 361ba86734fb2821a6adcfdf775db8abd04e0de0, e_acosf.c.
// Original SHA256: 8f85d8f17ef06dd2fcfe19b9b7f1d0891d37312b1fe299c8e79bc8466c96e468.
// Keep binary32 intermediates and volatile pio2_lo. No process-wide desktop libm override:
// this translation unit is linked only into ByteInk's isolated Emscripten module.
#include <bit>
#include <cmath>
#include <cstdint>

namespace byteink::wasm_math {
static const float
one =  1.0000000000e+00, /* 0x3F800000 */
pi =  3.1415925026e+00, /* 0x40490fda */
pio2_hi =  1.5707962513e+00; /* 0x3fc90fda */
static volatile float
pio2_lo =  7.5497894159e-08; /* 0x33a22168 */
static const float
pS0 =  1.6666586697e-01,
pS1 = -4.2743422091e-02,
pS2 = -8.6563630030e-03,
qS1 = -7.0662963390e-01;

float Acos(float x)
{
	float z,p,q,r,w,s,c,df;
	int32_t hx,ix;
	hx = std::bit_cast<int32_t>(x);
	ix = hx&0x7fffffff;
	if(ix>=0x3f800000) {		/* |x| >= 1 */
	    if(ix==0x3f800000) {	/* |x| == 1 */
		if(hx>0) return 0.0;	/* acos(1) = 0 */
		else return pi+(float)2.0*pio2_lo;	/* acos(-1)= pi */
	    }
	    return (x-x)/(x-x);		/* acos(|x|>1) is NaN */
	}
	if(ix<0x3f000000) {	/* |x| < 0.5 */
	    if(ix<=0x32800000) return pio2_hi+pio2_lo;/*if|x|<2**-26*/
	    z = x*x;
	    p = z*(pS0+z*(pS1+z*pS2));
	    q = one+z*qS1;
	    r = p/q;
	    return pio2_hi - (x - (pio2_lo-x*r));
	} else  if (hx<0) {		/* x < -0.5 */
	    z = (one+x)*(float)0.5;
	    p = z*(pS0+z*(pS1+z*pS2));
	    q = one+z*qS1;
	    s = sqrtf(z);
	    r = p/q;
	    w = r*s-pio2_lo;
	    return pi - (float)2.0*(s+w);
	} else {			/* x > 0.5 */
	    int32_t idf;
	    z = (one-x)*(float)0.5;
	    s = sqrtf(z);
	    df = s;
	    idf = std::bit_cast<int32_t>(df);
	    df = std::bit_cast<float>(idf & static_cast<int32_t>(0xfffff000));
	    c  = (z-df*df)/(s+df);
	    p = z*(pS0+z*(pS1+z*pS2));
	    q = one+z*qS1;
	    r = p/q;
	    w = r*s+c;
	    return (float)2.0*(df+w);
	}
}

}  // namespace byteink::wasm_math

#if defined(__EMSCRIPTEN__)
extern "C" float acosf(float value) { return byteink::wasm_math::Acos(value); }
#endif
