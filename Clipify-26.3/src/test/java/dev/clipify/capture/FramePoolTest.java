package dev.clipify.capture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FramePoolTest {

	@Test
	void handsOutUpToCapacityThenReturnsNull() {
		FramePool pool = new FramePool(1024, 3);
		ByteBuffer a = pool.acquire();
		ByteBuffer b = pool.acquire();
		ByteBuffer c = pool.acquire();
		assertNotNull(a);
		assertNotNull(b);
		assertNotNull(c);
		// Fourth acquisition exceeds capacity: the pool refuses rather than growing without bound.
		assertNull(pool.acquire());
	}

	@Test
	void releasedBuffersAreReused() {
		FramePool pool = new FramePool(1024, 2);
		ByteBuffer a = pool.acquire();
		pool.acquire();
		assertNull(pool.acquire());

		pool.release(a);
		ByteBuffer reused = pool.acquire();
		assertSame(a, reused, "a released buffer should come back out of the pool");
	}

	@Test
	void acquiredBufferIsClearedAndCorrectlySized() {
		FramePool pool = new FramePool(2048, 1);
		ByteBuffer buf = pool.acquire();
		assertNotNull(buf);
		assertEquals(2048, buf.capacity());
		assertEquals(0, buf.position());
		assertEquals(2048, buf.limit());
	}

	@Test
	void wrongSizedBufferIsRejectedOnRelease() {
		FramePool pool = new FramePool(1024, 2);
		pool.acquire();
		pool.acquire();
		// A buffer from a previous resolution must not poison the pool.
		pool.release(ByteBuffer.allocateDirect(512));
		// Capacity is still exhausted; the stray buffer was not accepted.
		assertNull(pool.acquire());
	}

	@Test
	void releasingNullIsIgnored() {
		FramePool pool = new FramePool(64, 1);
		pool.release(null);
		assertNotNull(pool.acquire());
	}

	@Test
	void clearResetsTheAllocationCounter() {
		FramePool pool = new FramePool(64, 1);
		pool.acquire();
		assertNull(pool.acquire());
		pool.clear();
		// After a resolution change the pool starts fresh.
		assertNotNull(pool.acquire());
	}
}
