package dev.clipify.capture;

import java.nio.ByteBuffer;

/**
 * One BGRA frame handed from the render thread to the encoder thread.
 *
 * @param pixels     pooled direct buffer, positioned at 0 and limited to the frame size
 * @param captureNanos {@link System#nanoTime()} at the moment the frame was presented, used by the
 *                     encoder thread to keep the output stream constant-frame-rate
 */
public record CapturedFrame(ByteBuffer pixels, long captureNanos) {}
