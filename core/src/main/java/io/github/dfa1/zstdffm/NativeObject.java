package io.github.dfa1.zstdffm;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;

/// Base class for wrappers that own a native zstd pointer.
///
/// The pointer lives in an {@link AtomicReference} that is swapped to
/// {@link MemorySegment#NULL} on {@link #close()}, so {@link #tryClose} runs
/// exactly once even under concurrent or repeated close calls.
public abstract class NativeObject implements AutoCloseable {

	private final AtomicReference<MemorySegment> ptr;

	protected NativeObject(MemorySegment owningPointer) {
		this.ptr = new AtomicReference<>(owningPointer);
	}

	/// @return the live native pointer
	/// @throws IllegalStateException if this object has been closed
	protected final MemorySegment ptr() {
		MemorySegment p = ptr.get();
		if (MemorySegment.NULL.equals(p)) {
			throw new IllegalStateException("native object is closed");
		}
		return p;
	}

	@Override
	public final void close() {
		MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
		if (!MemorySegment.NULL.equals(p)) {
			try {
				tryClose(p);
			} catch (Throwable ignored) {
				// destructors must not throw
			}
		}
	}

	/// Releases the native resource. Called exactly once with a non-NULL pointer.
	protected abstract void tryClose(MemorySegment ptr) throws Throwable;
}
