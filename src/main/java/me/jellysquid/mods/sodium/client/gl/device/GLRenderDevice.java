package me.jellysquid.mods.sodium.client.gl.device;

import me.jellysquid.mods.sodium.client.gl.array.GlVertexArray;
import me.jellysquid.mods.sodium.client.gl.buffer.*;
import me.jellysquid.mods.sodium.client.gl.functions.DeviceFunctions;
import me.jellysquid.mods.sodium.client.gl.state.GlStateTracker;
import me.jellysquid.mods.sodium.client.gl.sync.GlFence;
import me.jellysquid.mods.sodium.client.gl.tessellation.*;
import me.jellysquid.mods.sodium.client.gl.util.EnumBitField;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import java.nio.ByteBuffer;

public class GLRenderDevice implements RenderDevice {
    private final GlStateTracker stateTracker = new GlStateTracker();
    private final CommandList commandList = new ImmediateCommandList(this.stateTracker);
    private final DrawCommandList drawCommandList = new ImmediateDrawCommandList();

    private final DeviceFunctions functions = new DeviceFunctions(this);

    private boolean isActive;
    private GlTessellation activeTessellation;

    @Override
    public CommandList createCommandList() {
        GLRenderDevice.this.checkDeviceActive();

        return this.commandList;
    }

    @Override
    public void makeActive() {
        if (this.isActive) {
            return;
        }

        // 1.12.2: no static VertexBuffer.unbind(); reset the array buffer binding directly (LWJGL3).
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);

        this.stateTracker.clear();
        this.isActive = true;
    }

    @Override
    public void makeInactive() {
        if (!this.isActive) {
            return;
        }

        // 1.12.2: no static VertexBuffer.unbind(); reset the array buffer binding directly (LWJGL3).
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);

        this.stateTracker.clear();
        this.isActive = false;
    }

    @Override
    public void notifyExternalStateReset() {
        this.stateTracker.clear();
    }

    @Override
    public GLCapabilities getCapabilities() {
        return GL.getCapabilities();
    }

    @Override
    public DeviceFunctions getDeviceFunctions() {
        return this.functions;
    }

    private void checkDeviceActive() {
        if (!this.isActive) {
            throw new IllegalStateException("Tried to access device from unmanaged context");
        }
    }

    private class ImmediateCommandList implements CommandList {
        private final GlStateTracker stateTracker;

        private ImmediateCommandList(GlStateTracker stateTracker) {
            this.stateTracker = stateTracker;
        }

        @Override
        public void bindVertexArray(GlVertexArray array) {
            if (this.stateTracker.makeVertexArrayActive(array)) {
                GL30C.glBindVertexArray(array.handle());
            }
        }

        @Override
        public void uploadData(GlMutableBuffer glBuffer, ByteBuffer byteBuffer, GlBufferUsage usage) {
            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, glBuffer);

            GL20C.glBufferData(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), byteBuffer, usage.getId());
            glBuffer.setSize(byteBuffer.remaining());
        }

        @Override
        public void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes) {
            this.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, src);
            this.bindBuffer(GlBufferTarget.COPY_WRITE_BUFFER, dst);

            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, readOffset, writeOffset, bytes);
        }

        @Override
        public void bindBuffer(GlBufferTarget target, GlBuffer buffer) {
            if (this.stateTracker.makeBufferActive(target, buffer)) {
                GL20C.glBindBuffer(target.getTargetParameter(), buffer.handle());
            }
        }

        @Override
        public void unbindVertexArray() {
            if (this.stateTracker.makeVertexArrayActive(null)) {
                GL30C.glBindVertexArray(GlVertexArray.NULL_ARRAY_ID);
            }
        }

        @Override
        public void allocateStorage(GlMutableBuffer buffer, long bufferSize, GlBufferUsage usage) {
            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);

            GL20C.glBufferData(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), bufferSize, usage.getId());
            buffer.setSize(bufferSize);
        }

        @Override
        public void deleteBuffer(GlBuffer buffer) {
            if (buffer.getActiveMapping() != null) {
                this.unmap(buffer.getActiveMapping());
            }

            this.stateTracker.notifyBufferDeleted(buffer);

            int handle = buffer.handle();
            buffer.invalidateHandle();

            GL20C.glDeleteBuffers(handle);
        }

        @Override
        public void deleteVertexArray(GlVertexArray vertexArray) {
            this.stateTracker.notifyVertexArrayDeleted(vertexArray);

            int handle = vertexArray.handle();
            vertexArray.invalidateHandle();

            GL30C.glDeleteVertexArrays(handle);
        }

        @Override
        public void flush() {
            // NO-OP
        }

        @Override
        public DrawCommandList beginTessellating(GlTessellation tessellation) {
            GLRenderDevice.this.activeTessellation = tessellation;
            GLRenderDevice.this.activeTessellation.bind(GLRenderDevice.this.commandList);

            return GLRenderDevice.this.drawCommandList;
        }

        @Override
        public void deleteTessellation(GlTessellation tessellation) {
            tessellation.delete(this);
        }

        @Override
        public GlBufferMapping mapBuffer(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags) {
            if (buffer.getActiveMapping() != null) {
                throw new IllegalStateException("Buffer is already mapped");
            }

            if (flags.contains(GlBufferMapFlags.PERSISTENT) && !(buffer instanceof GlImmutableBuffer)) {
                throw new IllegalStateException("Tried to map mutable buffer as persistent");
            }

            // TODO: speed this up?
            if (buffer instanceof GlImmutableBuffer) {
                EnumBitField<GlBufferStorageFlags> bufferFlags = ((GlImmutableBuffer) buffer).getFlags();

                if (flags.contains(GlBufferMapFlags.PERSISTENT) && !bufferFlags.contains(GlBufferStorageFlags.PERSISTENT)) {
                    throw new IllegalArgumentException("Tried to map non-persistent buffer as persistent");
                }

                if (flags.contains(GlBufferMapFlags.WRITE) && !bufferFlags.contains(GlBufferStorageFlags.MAP_WRITE)) {
                    throw new IllegalStateException("Tried to map non-writable buffer as writable");
                }

                if (flags.contains(GlBufferMapFlags.READ) && !bufferFlags.contains(GlBufferStorageFlags.MAP_READ)) {
                    throw new IllegalStateException("Tried to map non-readable buffer as readable");
                }
            }

            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);

            ByteBuffer buf = GL32C.glMapBufferRange(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), offset, length, flags.getBitField());

            if (buf == null) {
                throw new RuntimeException("Failed to map buffer");
            }

            GlBufferMapping mapping = new GlBufferMapping(buffer, buf);

            buffer.setActiveMapping(mapping);

            return mapping;
        }

        @Override
        public void unmap(GlBufferMapping map) {
            checkMapDisposed(map);

            GlBuffer buffer = map.getBufferObject();

            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);
            GL32C.glUnmapBuffer(GlBufferTarget.ARRAY_BUFFER.getTargetParameter());

            buffer.setActiveMapping(null);
            map.dispose();
        }

        @Override
        public void flushMappedRange(GlBufferMapping map, int offset, int length) {
            checkMapDisposed(map);

            GlBuffer buffer = map.getBufferObject();

            this.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, buffer);
            GL32C.glFlushMappedBufferRange(GlBufferTarget.COPY_READ_BUFFER.getTargetParameter(), offset, length);
        }

        @Override
        public GlFence createFence() {
            return new GlFence(GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
        }

        private void checkMapDisposed(GlBufferMapping map) {
            if (map.isDisposed()) {
                throw new IllegalStateException("Buffer mapping is already disposed");
            }
        }

        @Override
        public GlMutableBuffer createMutableBuffer() {
            return new GlMutableBuffer();
        }

        @Override
        public GlImmutableBuffer createImmutableBuffer(long bufferSize, EnumBitField<GlBufferStorageFlags> flags) {
            GlImmutableBuffer buffer = new GlImmutableBuffer(flags);

            this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, buffer);
            GLRenderDevice.this.functions.getBufferStorageFunctions()
                    .createBufferStorage(GlBufferTarget.ARRAY_BUFFER, bufferSize, flags);

            return buffer;
        }

        @Override
        public GlTessellation createTessellation(GlPrimitiveType primitiveType, TessellationBinding[] bindings) {
            GlVertexArrayTessellation tessellation = new GlVertexArrayTessellation(new GlVertexArray(), primitiveType, bindings);
            tessellation.init(this);

            return tessellation;
        }
    }

    private class ImmediateDrawCommandList implements DrawCommandList {
        // Reused across frames for INDIRECT mode: a driver-side command buffer + a native staging region.
        private int indirectBuffer = 0;
        private long indirectScratch = 0L;
        private int indirectScratchCommands = 0;

        public ImmediateDrawCommandList() {

        }

        @Override
        public void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType) {
            GlPrimitiveType primitiveType = GLRenderDevice.this.activeTessellation.getPrimitiveType();

            GL32C.nglMultiDrawElementsBaseVertex(primitiveType.getId(),
                    batch.pElementCount,
                    indexType.getFormatId(),
                    batch.pElementPointer,
                    batch.size(),
                    batch.pBaseVertex);
        }

        @Override
        public void drawElementsIndividual(MultiDrawBatch batch, GlIndexType indexType) {
            int mode = GLRenderDevice.this.activeTessellation.getPrimitiveType().getId();
            int type = indexType.getFormatId();
            int size = batch.size();

            for (int i = 0; i < size; i++) {
                int count = MemoryUtil.memGetInt(batch.pElementCount + ((long) i * Integer.BYTES));
                long indices = MemoryUtil.memGetAddress(batch.pElementPointer + ((long) i * Pointer.POINTER_SIZE));
                int baseVertex = MemoryUtil.memGetInt(batch.pBaseVertex + ((long) i * Integer.BYTES));

                GL32C.glDrawElementsBaseVertex(mode, count, type, indices, baseVertex);
            }
        }

        @Override
        public void multiDrawElementsIndirect(MultiDrawBatch batch, GlIndexType indexType) {
            int size = batch.size();
            if (size <= 0) {
                return;
            }

            int mode = GLRenderDevice.this.activeTessellation.getPrimitiveType().getId();
            int type = indexType.getFormatId();
            int indexStride = indexType.getStride();

            // Each DrawElementsIndirectCommand is 5 uints (20 bytes): count, instanceCount, firstIndex, baseVertex, baseInstance.
            final int CMD_SIZE = 20;
            if (this.indirectScratch == 0L || this.indirectScratchCommands < size) {
                if (this.indirectScratch != 0L) {
                    MemoryUtil.nmemFree(this.indirectScratch);
                }
                this.indirectScratch = MemoryUtil.nmemAlloc((long) size * CMD_SIZE);
                this.indirectScratchCommands = size;
            }

            long cmds = this.indirectScratch;
            for (int i = 0; i < size; i++) {
                int count = MemoryUtil.memGetInt(batch.pElementCount + ((long) i * Integer.BYTES));
                long byteOffset = MemoryUtil.memGetAddress(batch.pElementPointer + ((long) i * Pointer.POINTER_SIZE));
                int firstIndex = (int) (byteOffset / indexStride);
                int baseVertex = MemoryUtil.memGetInt(batch.pBaseVertex + ((long) i * Integer.BYTES));

                long c = cmds + ((long) i * CMD_SIZE);
                MemoryUtil.memPutInt(c, count);            // count
                MemoryUtil.memPutInt(c + 4, 1);            // instanceCount
                MemoryUtil.memPutInt(c + 8, firstIndex);   // firstIndex
                MemoryUtil.memPutInt(c + 12, baseVertex);  // baseVertex
                MemoryUtil.memPutInt(c + 16, 0);           // baseInstance
            }

            if (this.indirectBuffer == 0) {
                this.indirectBuffer = GL15C.glGenBuffers();
            }

            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, this.indirectBuffer);
            GL15C.nglBufferData(GL40C.GL_DRAW_INDIRECT_BUFFER, (long) size * CMD_SIZE, cmds, GL15C.GL_STREAM_DRAW);
            GL43C.glMultiDrawElementsIndirect(mode, type, 0L, size, 0);
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        }

        @Override
        public void endTessellating() {
            GLRenderDevice.this.activeTessellation.unbind(GLRenderDevice.this.commandList);
            GLRenderDevice.this.activeTessellation = null;
        }

        @Override
        public void flush() {
            if (GLRenderDevice.this.activeTessellation != null) {
                this.endTessellating();
            }
        }
    }
}
