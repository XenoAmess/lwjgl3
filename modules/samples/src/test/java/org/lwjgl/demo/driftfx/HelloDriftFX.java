/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.driftfx;

import javafx.application.*;
import javafx.scene.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.stage.*;
import org.eclipse.fx.drift.*;
import org.lwjgl.*;
import org.lwjgl.demo.opengl.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.lwjgl.driftfx.DriftFX.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class HelloDriftFX extends Application {

    private final AtomicBoolean  aliveFlag = new AtomicBoolean(true);
    private final CountDownLatch exitLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        DriftFXSurface.initialize();

        BorderPane root = new BorderPane();

        DriftFXSurface surface = new DriftFXSurface();
        root.setCenter(surface);

        Scene scene = new Scene(root);
        scene.setFill(Color.BLACK);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.ESCAPE),
            () -> primaryStage.fireEvent(new WindowEvent(primaryStage, WindowEvent.WINDOW_CLOSE_REQUEST))
        );

        primaryStage.setScene(scene);

        primaryStage.setWidth(300);
        primaryStage.setHeight(300);
        primaryStage.show();
        primaryStage.setTitle("DriftFX Gears");
        primaryStage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, windowEvent -> {
            // notify render thread that we're exiting
            aliveFlag.set(false);
            try {
                // block until render thread has cleaned up
                exitLatch.await();
            } catch (InterruptedException ignored) {
            }
        });

        new RenderThread(
            aliveFlag,
            exitLatch,
            surface.getNativeSurfaceHandle()
        ).start();
    }

    private static class RenderThread extends Thread {

        private final AtomicBoolean  aliveFlag;
        private final CountDownLatch exitLatch;

        private final long surface;

        private final long transferMode;

        private long context;

        private int fbo;
        private int rbo;

        private GLXGears gears;

        private int width;
        private int height;

        RenderThread(AtomicBoolean aliveFlag, CountDownLatch exitLatch, long surfaceId) {
            super("HelloDriftFX-Render-Thread");

            this.aliveFlag = aliveFlag;
            this.exitLatch = exitLatch;

            this.surface = driftfxGetSurface(driftfxGet(), surfaceId);

            long transferMode = driftfxSurfaceGetPlatformDefaultTransferMode();
            try (MemoryStack stack = stackPush()) {
                PointerBuffer modes = stack.mallocPointer((int)driftfxSurfaceGetAvailableTransferModes(null));
                driftfxSurfaceGetAvailableTransferModes(modes);
                for (int i = 0; i < modes.limit(); i++) {
                    long mode = modes.get(i);
                    try (MemoryStack frame = stack.push()) {
                        ByteBuffer name = frame.malloc((int)driftfxTransferModeName(mode, null));
                        driftfxTransferModeName(mode, name);
                        if (memASCII(name).startsWith("NVDX")) {
                            transferMode = mode;
                        }
                    }
                }
            }
            this.transferMode = transferMode;
        }

        @Override public void run() {
            initGLState();
            renderLoop();
            cleanup();
        }

        private void initGLState() {
            driftfxSurfaceInitialize(surface);

            context = driftfxSurfaceGetContext(surface);
            driftfxGLContextSetCurrent(context);

            try (MemoryStack frame = stackPush()) {
                ByteBuffer name = frame.malloc((int)driftfxGLContextGetName(context, null));
                driftfxGLContextGetName(context, name);
                System.out.println("GLContext name: " + memASCII(name));
            }

            GL.createCapabilities();

            this.fbo = glGenFramebuffers();
            this.rbo = glGenRenderbuffers();

            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            checkFBOSize();
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rbo);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            this.gears = new GLXGears();
        }

        private void renderLoop() {
            while (aliveFlag.get()) {
                checkFBOSize();

                long target = driftfxSurfaceAcquire(surface, width, height, transferMode);

                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, driftfxRenderTargetGetGLTexture(target), 0);

                gears.setSize(width, height);
                gears.render();
                gears.animate();

                glFlush();

                glBindFramebuffer(GL_FRAMEBUFFER, 0);

                driftfxSurfacePresent(surface, target, DRIFTFX_CENTER);
                //Sync.sync(60);
            }
        }

        private void cleanup() {
            glDeleteRenderbuffers(rbo);
            glDeleteFramebuffers(fbo);

            GL.setCapabilities(null);

            driftfxSurfaceCleanup(surface);
            driftfxGLContextUnsetCurrent(context);


            exitLatch.countDown();
        }

        private void checkFBOSize() {
            int width  = driftfxSurfaceGetWidth(surface);
            int height = driftfxSurfaceGetHeight(surface);

            if (width != this.width || height != this.height) {
                this.width = width;
                this.height = height;
                glBindRenderbuffer(GL_RENDERBUFFER, rbo);
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT, this.width, this.height);
                glBindRenderbuffer(GL_RENDERBUFFER, 0);
            }
        }

    }

    static final class Sync {

        /** number of nano seconds in a second */
        private static final long NANOS_IN_SECOND = 1000L * 1000L * 1000L;

        /** The time to sleep/yield until the next frame */
        private static long nextFrame = 0;

        /** whether the initialisation code has run */
        private static boolean initialised = false;

        /** for calculating the averages the previous sleep/yield times are stored */
        private static RunningAvg sleepDurations = new RunningAvg();
        private static RunningAvg yieldDurations = new RunningAvg();

        /**
         * An accurate sync method that will attempt to run at a constant frame rate.
         *
         * <p>It should be called once every frame.</p>
         *
         * @param fps - the desired frame rate, in frames per second
         */
        static void sync(int fps) {
            if (fps <= 0) {
                return;
            }
            if (!initialised) {
                initialise();
            }

            try {
                // sleep until the average sleep time is greater than the time remaining till nextFrame
                for (long t0 = System.nanoTime(), t1; (nextFrame - t0) > sleepDurations.avg(); t0 = t1) {
                    Thread.sleep(1);
                    sleepDurations.add((t1 = System.nanoTime()) - t0); // update average sleep time
                }

                // slowly dampen sleep average if too high to avoid yielding too much
                sleepDurations.dampenForLowResTicker();

                // yield until the average yield time is greater than the time remaining till nextFrame
                for (long t0 = System.nanoTime(), t1; (nextFrame - t0) > yieldDurations.avg(); t0 = t1) {
                    Thread.yield();
                    yieldDurations.add((t1 = System.nanoTime()) - t0); // update average yield time
                }
            } catch (InterruptedException ignored) {
            }

            // schedule next frame, drop frame(s) if already too late for next frame
            nextFrame = Math.max(nextFrame + NANOS_IN_SECOND / fps, System.nanoTime());
        }

        /**
         * This method will initialise the sync method by setting initial
         * values for sleepDurations/yieldDurations and nextFrame.
         *
         * If running on windows it will start the sleep timer fix.
         */
        private static void initialise() {
            initialised = true;

            sleepDurations.init(1000 * 1000);
            yieldDurations.init((int)(-(System.nanoTime() - System.nanoTime()) * 1.333));

            nextFrame = System.nanoTime();

            String osName = System.getProperty("os.name");

            if (osName.startsWith("Win")) {
                // On windows the sleep functions can be highly inaccurate by
                // over 10ms making in unusable. However it can be forced to
                // be a bit more accurate by running a separate sleeping daemon
                // thread.
                Thread timerAccuracyThread = new Thread(() -> {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (Exception ignored) {
                    }
                });

                timerAccuracyThread.setName("LWJGL Timer");
                timerAccuracyThread.setDaemon(true);
                timerAccuracyThread.start();
            }
        }

        private static class RunningAvg {

            private static final int SLOTS = 10;

            private final long[] slots;
            private       int    offset;

            private static final long  DAMPEN_THRESHOLD = 10 * 1000L * 1000L; // 10ms
            private static final float DAMPEN_FACTOR    = 0.9f; // don't change: 0.9f is exactly right!

            RunningAvg() {
                this.slots = new long[SLOTS];
                this.offset = 0;
            }

            void init(long value) {
                while (offset < SLOTS) {
                    slots[offset++] = value;
                }
            }

            void add(long value) {
                slots[offset++ % SLOTS] = value;
                offset %= SLOTS;
            }

            long avg() {
                long sum = 0;
                for (long slot : slots) {
                    sum += slot;
                }
                return sum / SLOTS;
            }

            void dampenForLowResTicker() {
                if (DAMPEN_THRESHOLD < avg()) {
                    for (int i = 0; i < slots.length; i++) {
                        slots[i] *= DAMPEN_FACTOR;
                    }
                }
            }
        }
    }

}