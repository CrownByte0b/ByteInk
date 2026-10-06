package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;

/** Small x86_64 ABI boundary, independent of Ink's JNI library and engine pin. */
final class NativeCalls {
    private final List<SymbolLookup> libraries;
    private final Map<String, MethodHandle> functions = new HashMap<>();
    NativeCalls(Arena arena, String... names) {
        libraries = Arrays.stream(names).map(name -> SymbolLookup.libraryLookup(name, arena)).toList();
        if (ValueLayout.ADDRESS.byteSize() != 8) throw new UnsupportedOperationException("Native pen capture requires x86_64");
    }
    synchronized MethodHandle function(String name, MemoryLayout result, MemoryLayout... args) {
        return functions.computeIfAbsent(name, key -> {
            MemorySegment symbol = libraries.stream().map(lib -> lib.find(key)).flatMap(Optional::stream)
                    .findFirst().orElseThrow(() -> new UnsupportedOperationException("Missing native function: " + key));
            return Linker.nativeLinker().downcallHandle(symbol,
                    result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
        });
    }
    Object call(String name, MemoryLayout result, Object... args) {
        MemoryLayout[] layouts = Arrays.stream(args).map(arg -> arg instanceof Integer ? ValueLayout.JAVA_INT
                : arg instanceof Long ? ValueLayout.JAVA_LONG : ValueLayout.ADDRESS).toArray(MemoryLayout[]::new);
        try { return function(name, result, layouts).invokeWithArguments(args); }
        catch (Throwable failure) { throw new IllegalStateException(name + " failed", failure); }
    }
    int integer(String name, Object... args) { return (int) call(name, ValueLayout.JAVA_INT, args); }
    long longValue(String name, Object... args) { return (long) call(name, ValueLayout.JAVA_LONG, args); }
    MemorySegment pointer(String name, Object... args) { return (MemorySegment) call(name, ValueLayout.ADDRESS, args); }
    void procedure(String name, Object... args) { call(name, null, args); }
}
