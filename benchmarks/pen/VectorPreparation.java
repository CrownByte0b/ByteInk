import jdk.incubator.vector.*;

/** Optional Vector API experiment; loaded only for the vector candidate. */
public final class VectorPreparation {
    static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    static final int LANES = SPECIES.length();
    static final int[] INPUT = offsets(15), POSITION = offsets(2), VARYING = offsets(16);
    static final FloatVector ZERO = FloatVector.zero(SPECIES), ONE = FloatVector.broadcast(SPECIES, 1f);
    static long vectorBlocks, scalarFallbackVertices;
    static int[] offsets(int stride) {
        int[] result = new int[LANES]; for (int i = 0; i < LANES; i++) result[i] = i * stride; return result;
    }
    static FloatVector load(float[] input, int vertex, int attribute) {
        return FloatVector.fromArray(SPECIES, input, vertex*15+attribute, INPUT, 0);
    }
    static FloatVector clamp(FloatVector value) {
        return value.blend(0f, value.compare(VectorOperators.LT, 0f)).blend(1f, value.compare(VectorOperators.GT, 1f));
    }
    static FloatVector sign(FloatVector value) {
        return ONE.blend(-1f, value.compare(VectorOperators.LT, 0f)).blend(value, value.compare(VectorOperators.EQ, 0f));
    }
    static FloatVector distance(FloatVector dx, FloatVector dy, MeshPreparation.Context c) {
        FloatVector rx = dy.mul(-c.a).add(dx.mul(c.b)), ry = dy.mul(-c.d).add(dx.mul(c.e));
        return dx.mul(dx).add(dy.mul(dy)).mul(c.det)
            .div(rx.mul(rx).add(ry.mul(ry)).sqrt().max(.000001f)).max(.000001f);
    }
    static boolean finite(FloatVector value) { return value.test(VectorOperators.IS_FINITE).allTrue(); }
    static void store(FloatVector value, float[] output, int vertex, int attribute, VectorMask<Float> mask) {
        value.intoArray(output, vertex*16+attribute, VARYING, 0, mask);
    }
    static void prepare(float[] input, int count, MeshPreparation.Context c, float[] positions, float[] varying, boolean[] changed) {
        // Vector transcendental operations are not promised to reproduce scalar Math raw bits.
        if (c.stamp != null || !Float.isFinite(c.a) || !Float.isFinite(c.b)
                || !Float.isFinite(c.d) || !Float.isFinite(c.e) || !Float.isFinite(c.det)
                || !Float.isFinite(c.color.getR()) || !Float.isFinite(c.color.getG())
                || !Float.isFinite(c.color.getB()) || !Float.isFinite(c.color.getA())
                || !Float.isFinite(c.baseY) || !Float.isFinite(c.baseHue) || !Float.isFinite(c.baseChroma)) {
            scalarFallbackVertices += count;
            MeshPreparation.scalar(input, 0, count, c, positions, varying, changed); return;
        }
        float[] hues = c.hsl ? new float[LANES] : null, chromas = c.hsl ? new float[LANES] : null;
        float[] shiftedI = c.hsl ? new float[LANES] : null, shiftedQ = c.hsl ? new float[LANES] : null;
        int vertex = 0;
        for (; vertex < SPECIES.loopBound(count); vertex += LANES) {
            long bits = -1L;
            if (changed != null) {
                bits = 0; for (int lane = 0; lane < LANES; lane++) if (changed[vertex+lane]) bits |= 1L << lane;
                if (bits == 0) continue;
            }
            VectorMask<Float> mask = VectorMask.fromLong(SPECIES, bits);
            FloatVector x = load(input, vertex, 0), y = load(input, vertex, 1), opacity = load(input, vertex, 2);
            FloatVector sx = load(input, vertex, 6), sy = load(input, vertex, 7), sl = load(input, vertex, 8);
            FloatVector fx = load(input, vertex, 9), fy = load(input, vertex, 10), fl = load(input, vertex, 11);
            if (!finite(x) || !finite(y) || !finite(opacity) || !finite(sx) || !finite(sy)
                    || !finite(sl) || !finite(fx) || !finite(fy) || !finite(fl)) {
                scalarFallbackVertices += LANES;
                MeshPreparation.scalar(input, vertex, vertex+LANES, c, positions, varying, changed); continue;
            }
            FloatVector sidePixels = distance(sx, sy, c), forwardPixels = distance(fx, fy, c);
            FloatVector target = clamp(sidePixels.sub(.5f).mul(2f)).mul(.707107f - .5f).add(.5f);
            FloatVector sideTarget = target.div(sidePixels), forwardTarget = target.div(forwardPixels);
            FloatVector sideMargin = sl.abs().sub(1f).max(0f).mul(4f / 126f);
            FloatVector forwardMargin = fl.abs().sub(1f).max(0f).mul(4f / 126f);
            FloatVector sideOutset = sideTarget.min(sideMargin).sub(sideTarget)
                .mul(clamp(sidePixels.mul(4f).sub(1f))).add(sideTarget);
            FloatVector forwardOutset = forwardTarget.min(forwardMargin);
            FloatVector sox = sign(sl).mul(sideOutset).mul(sx), soy = sign(sl).mul(sideOutset).mul(sy);
            FloatVector fox = sign(fl).mul(forwardOutset).mul(fx), foy = sign(fl).mul(forwardOutset).mul(fy);
            FloatVector common = clamp(sox.mul(fox).add(soy.mul(foy)).div(fox.mul(fox).add(foy.mul(foy)).max(.000001f)));
            FloatVector px = x.add(sox).add(ONE.sub(common).mul(fox)), py = y.add(soy).add(ONE.sub(common).mul(foy));
            FloatVector alpha = clamp(opacity.add(1f).mul(c.color.getA()));
            FloatVector sideRatio = sideOutset.div(sideTarget), forwardRatio = forwardOutset.div(forwardTarget);
            // Preserve scalar NaN payload/exceptional arithmetic via scalar blocks, without fast math.
            if (!finite(px) || !finite(py) || !finite(alpha) || !finite(sidePixels) || !finite(forwardPixels)
                    || !finite(sideRatio) || !finite(forwardRatio)) {
                scalarFallbackVertices += LANES;
                MeshPreparation.scalar(input, vertex, vertex+LANES, c, positions, varying, changed); continue;
            }
            FloatVector red, green, blue;
            if (c.hsl) {
                FloatVector hueShift=load(input,vertex,3), chromaShift=load(input,vertex,4), yShift=load(input,vertex,5);
                if (!finite(hueShift) || !finite(chromaShift) || !finite(yShift)) {
                    scalarFallbackVertices += LANES;
                    MeshPreparation.scalar(input,vertex,vertex+LANES,c,positions,varying,changed); continue;
                }
                FloatVector.broadcast(SPECIES,c.baseHue).sub(hueShift.mul(2f*(float)Math.PI)).intoArray(hues,0);
                chromaShift.add(1f).mul(c.baseChroma).intoArray(chromas,0);
                for (int lane=0;lane<LANES;lane++) {
                    shiftedI[lane]=chromas[lane]*(float)Math.cos(hues[lane]);
                    shiftedQ[lane]=chromas[lane]*(float)Math.sin(hues[lane]);
                }
                FloatVector i=FloatVector.fromArray(SPECIES,shiftedI,0), q=FloatVector.fromArray(SPECIES,shiftedQ,0);
                FloatVector yValue=yShift.add(c.baseY);
                red=yValue.add(i.mul(.956f)).add(q.mul(.621f)).mul(alpha);
                green=yValue.sub(i.mul(.272f)).sub(q.mul(.647f)).mul(alpha);
                blue=yValue.sub(i.mul(1.107f)).add(q.mul(1.704f)).mul(alpha);
                if (!finite(red) || !finite(green) || !finite(blue)) {
                    scalarFallbackVertices += LANES;
                    MeshPreparation.scalar(input,vertex,vertex+LANES,c,positions,varying,changed); continue;
                }
            } else {
                red=alpha.mul(c.color.getR()); green=alpha.mul(c.color.getG()); blue=alpha.mul(c.color.getB());
            }
            vectorBlocks++;
            px.intoArray(positions, vertex*2, POSITION, 0, mask);
            py.intoArray(positions, vertex*2+1, POSITION, 0, mask);
            store(red, varying, vertex, 0, mask); store(green, varying, vertex, 1, mask);
            store(blue, varying, vertex, 2, mask);
            store(alpha, varying, vertex, 3, mask); store(sidePixels, varying, vertex, 4, mask);
            store(forwardPixels, varying, vertex, 5, mask);
            FloatVector edge0 = ZERO.blend(1f, sl.compare(VectorOperators.GT, -.005f));
            FloatVector edge1 = ZERO.blend(1f, sl.compare(VectorOperators.LT, .005f));
            FloatVector edge2 = ZERO.blend(1f, fl.compare(VectorOperators.GT, -.005f));
            FloatVector edge3 = ZERO.blend(1f, fl.compare(VectorOperators.LT, .005f));
            store(edge0, varying, vertex, 6, mask); store(edge1, varying, vertex, 7, mask);
            store(edge2, varying, vertex, 8, mask); store(edge3, varying, vertex, 9, mask);
            store(target.mul(ONE.sub(edge0)).mul(sideRatio), varying, vertex, 10, mask);
            store(target.mul(ONE.sub(edge1)).mul(sideRatio), varying, vertex, 11, mask);
            store(target.mul(ONE.sub(edge2)).mul(forwardRatio), varying, vertex, 12, mask);
            store(target.mul(ONE.sub(edge3)).mul(forwardRatio), varying, vertex, 13, mask);
            store(px, varying, vertex, 14, mask); store(py, varying, vertex, 15, mask);
        }
        scalarFallbackVertices += count-vertex;
        MeshPreparation.scalar(input, vertex, count, c, positions, varying, changed);
    }
}

/*
 * Shader math adapted from google/ink at 96e50239e1c8955f7e222301661b595847d7de83,
 * ink/rendering/skia/common_internal/sksl_{vertex,fragment,common}_shader_helper_functions.h.
 * Copyright 2024 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
