// Runs directly against the compiled C++ engine; no npm packages or mocks.
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { test } from 'node:test';

const root = new URL('../../', import.meta.url);
const pins = await readFile(new URL('upstream/pins.properties', root), 'utf8');
const commit = /^google.ink.commit=(.+)$/m.exec(pins)[1];
const engineUrl = process.env.BYTEINK_WASM_ENGINE || new URL(`native/build/out/${commit}/wasm/byteink-engine.mjs`, root).href;
const { default: create } = await import(engineUrl);
const engine = await create();
const samples = new Float64Array([10, 20, 0, 1, -1, -1, -1, 0, 40, 20, 16, 1, -1, -1, -1, 0]);
const createStroke = (family = 0, inputs = samples) => engine.createStroke(family, 0xff000000, 4, .25, inputs);
const empty = new Float64Array();

test('loads the pinned engine and authors all four version 1 stock brushes', () => {
    assert.equal(engine.abiVersion(), 2);
    assert.equal(engine.engineCommit(), commit);
    for (let family = 0; family < 4; ++family) {
        const stroke = createStroke(family);
        try {
            assert.deepEqual(stroke.inputs(), samples);
            assert.ok(stroke.geometry()[0].meshes.some(mesh => mesh.triangles.length > 0));
        } finally { stroke.delete(); }
    }
});

test('owned geometry survives disposal and subsequent engine allocations', () => {
    const stroke = createStroke();
    const geometry = stroke.geometry();
    const original = geometry[0].meshes[0].positions.slice();
    assert.ok(stroke.contains(25, 20));
    assert.equal(stroke.contains(25, 50), false);
    stroke.delete();
    for (let i = 0; i < 200; ++i) {
        const next = createStroke();
        next.geometry();
        next.delete();
    }
    assert.deepEqual(geometry[0].meshes[0].positions, original);
    assert.throws(() => stroke.geometry(), /deleted/);
});

test('replaces predictions and finishes with real inputs only', () => {
    const live = engine.startStroke(0, 0xff000000, 4, .25);
    try {
        live.enqueue(samples, new Float64Array([60, 25, 32, 1, -1, -1, -1, 0]));
        live.update(32);
        assert.equal(live.realInputCount(), 2);
        assert.equal(live.predictedInputCount(), 1);
        live.enqueue(empty, new Float64Array([70, 35, 48, 1, -1, -1, -1, 0]));
        live.update(48);
        assert.equal(live.predictedInputCount(), 1);
        const snapshot = live.geometry();
        assert.ok(snapshot[0].meshes[0].triangles.length > 0);
        const finished = live.finish(48);
        try {
            assert.equal(live.predictedInputCount(), 0);
            assert.deepEqual(finished.inputs(), samples);
            const decoded = engine.decodeStroke(0, 0xff000000, 4, .25, finished.encodeInputs());
            try { assert.equal(decoded.inputs().length, samples.length); }
            finally { decoded.delete(); }
        } finally { finished.delete(); }
        assert.throws(() => live.enqueue(samples, empty), /FinishInputs/);
    } finally { live.delete(); }
});

test('finishing after the final rendered frame flushes stock modelling exactly', () => {
    for (let family = 0; family < 4; ++family) {
        const live = engine.startStroke(family, 0xff000000, 4, .25);
        const direct = createStroke(family);
        try {
            live.enqueue(samples, empty);
            live.update(16);
            const finished = live.finish(16);
            try { assert.deepEqual(finished.geometry(), direct.geometry()); }
            finally { finished.delete(); }
        } finally { live.delete(); direct.delete(); }
    }
});

test('empty geometry is safe to query and has no coverage', () => {
    const stroke = createStroke(0, empty);
    try {
        assert.equal(stroke.contains(0, 0), false);
        assert.equal(stroke.coverage(0, 0, 10, 10), 0);
        assert.deepEqual(stroke.inputs(), empty);
    } finally { stroke.delete(); }
});

test('rejects invalid values without poisoning later engine calls', () => {
    for (const invalid of [new Float64Array([1]), new Float64Array([NaN, 0, 0, 1, -1, -1, -1, 0]),
        new Float64Array([1, 0, 0, 9, -1, -1, -1, 0]), new Float64Array([1, 0, -1, 1, -1, -1, -1, 0])]) {
        assert.throws(() => createStroke(0, invalid));
    }
    assert.throws(() => engine.createStroke(99, 0xff000000, 4, .25, samples));
    assert.throws(() => engine.createStroke(0, 0xff000000, -1, .25, samples));
    assert.throws(() => engine.decodeStroke(0, 0xff000000, 4, .25, new Uint8Array([255])));
    const source = createStroke();
    const gzip = source.encodeAndroidInputs();
    source.delete();
    const badCrc = gzip.slice();
    badCrc[badCrc.length - 8] ^= 1;
    for (const invalid of [gzip.slice(0, -1), badCrc, new Uint8Array([...gzip, 0])]) {
        assert.throws(() => engine.decodeAndroidStroke(0, 0xff000000, 4, .25, invalid));
    }
    assert.throws(() => engine.decodeStrokeWithFamily(new Uint8Array([255]), 0xff000000, 4, .25, gzip));
    const live = engine.startStroke(0, 0xff000000, 4, .25);
    try {
        live.enqueue(samples, empty);
        live.update(16);
        assert.throws(() => live.update(-1));
        assert.throws(() => live.update(NaN));
        assert.throws(() => live.finish(8));
        live.enqueue(new Float64Array([60, 25, 32, 1, -1, -1, -1, 0]), empty);
        const finished = live.finish(32);
        finished.delete();
    } finally { live.delete(); }
    const stroke = createStroke();
    stroke.delete();
});

test('Android input bytes, closed boundaries, mesh vertices and coverage agree with the pinned engine', async () => {
    const cases = new URL('conformance/android/fixtures/matrix/cases/', root);
    let count = 0, maxGap = 0, reorderedMeshes = 0, rotatedOutlines = 0;
    const close = (actual, expected, label) => {
        assert.equal(actual.length, expected.length, label);
        for (let i = 0; i < actual.length; ++i) {
            const gap = Math.abs(actual[i] - expected[i]);
            maxGap = Math.max(maxGap, gap);
            assert.ok(gap <= 1e-4 + Math.abs(expected[i]) * 1e-6, `${label}[${i}]: ${actual[i]} != ${expected[i]}`);
        }
    };
    const closeOutline = (actual, expected, label) => {
        assert.equal(actual.length, expected.length, label);
        if (!actual.length) return;
        // A closed contour can begin at any one of its vertices. Preserve direction and every
        // repeated vertex; only the starting index is normalized, never the shape or tolerance.
        let offset = -1;
        for (let candidate = 0; candidate < expected.length; candidate += 2) {
            if (Array.from(actual).every((value, i) =>
                Math.abs(value - expected[(i + candidate) % expected.length]) <= 1e-4 + Math.abs(value) * 1e-6)) {
                offset = candidate;
                break;
            }
        }
        assert.ok(offset >= 0, `${label}: different closed boundary`);
        if (offset) ++rotatedOutlines;
        close(actual, expected.map((_, i) => expected[(i + offset) % expected.length]), label);
        if (process.env.BYTEINK_WASM_STRICT_TOPOLOGY) assert.equal(offset, 0, `${label}: contour start differs`);
    };
    for (const name of (await readdir(cases)).sort()) {
        const directory = new URL(`${name}/`, cases);
        const family = new Uint8Array(await readFile(new URL('family.pb', directory)));
        const reference = JSON.parse(await readFile(new URL('geometry.json', directory), 'utf8'));
        for (const row of reference.raw) {
            const bytes = await readFile(new URL(`${row.id}.inputs.pb.gz`, directory));
            const expectedGzip = await readFile(new URL(`${row.id}.roundtrip.pb.gz`, directory));
            const expectedBytes = gunzipSync(expectedGzip);
            const stroke = engine.decodeAndroidStrokeWithFamily(family, row.colorArgb >>> 0, row.size, .25, bytes);
            try {
                assert.deepEqual(Buffer.from(stroke.encodeAndroidInputs()), expectedGzip, `${name}/${row.id} Android gzip`);
                assert.deepEqual(expectedBytes.subarray(-5), Buffer.from([0x55, 0, 0, 0, 0]), 'Android private field 10');
                assert.deepEqual(Buffer.from(stroke.encodeInputs()), expectedBytes.subarray(0, -5), `${name}/${row.id} raw protobuf`);
                const geometry = stroke.geometry();
                assert.equal(geometry.length, row.groups.length, name);
                geometry.forEach((group, g) => {
                    const expected = row.groups[g];
                    assert.equal(group.meshes.length, expected.meshes.length, name);
                    assert.equal(group.outlines.length, expected.outlines.length, name);
                    group.meshes.forEach((mesh, m) => {
                        const golden = expected.meshes[m];
                        assert.equal(mesh.positions.length, golden.positions.length * 2, `${name} vertex count`);
                        assert.equal(mesh.triangles.length, golden.triangleIndices.length, `${name} triangle count`);
                        const indexIdentical = Array.from(mesh.triangles).every((index, t) => index === golden.triangleIndices[t]);
                        if (indexIdentical) close(mesh.positions, golden.positions.flat(), `${name} positions`);
                        else {
                            ++reorderedMeshes;
                            // Mesh packing/tessellation order differs in 32-bit Wasm for some
                            // round marker caps. Require a bijection of the actual vertices, then
                            // independently compare every boundary and native coverage below.
                            const unmatched = golden.positions.map(point => point.slice());
                            for (let v = 0; v < mesh.positions.length; v += 2) {
                                const point = mesh.positions.slice(v, v + 2);
                                const match = unmatched.findIndex(p => p.every((value, j) =>
                                    Math.abs(point[j] - value) <= 1e-4 + Math.abs(value) * 1e-6));
                                assert.ok(match >= 0, `${name}: different vertex position`);
                                close(point, unmatched[match], `${name} remapped position`);
                                unmatched.splice(match, 1);
                            }
                            assert.equal(unmatched.length, 0);
                        }
                        for (const index of mesh.triangles) assert.ok(index < mesh.positions.length / 2, `${name} index bounds`);
                        if (process.env.BYTEINK_WASM_STRICT_TOPOLOGY) {
                            assert.ok(indexIdentical, `${name}: triangle index order differs`);
                        }
                    });
                    group.outlines.forEach((outline, o) => closeOutline(outline, expected.outlines[o].flat(), `${name} outline`));
                });
                for (const probe of row.coverage) close([stroke.coverage(...probe.box)], [probe.value], `${name} native coverage`);
                ++count;
            } finally { stroke.delete(); }
        }
    }
    assert.equal(count, 840, 'Every Android matrix input stroke must run');
    console.log(JSON.stringify({ androidStrokes: count, largestGeometryGap: maxGap, reorderedMeshes, rotatedOutlines }));
});
