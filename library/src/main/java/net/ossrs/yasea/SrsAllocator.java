package net.ossrs.yasea;

import java.util.Arrays;

public final class SrsAllocator {

    public class Allocation {

        private byte[] data;
        private int size;

        public Allocation(int size) {
            this.data = new byte[size];
            this.size = 0;
        }

        public byte[] array() {
            return data;
        }

        public int size() {
            return size;
        }

        public void appendOffset(int offset) {
            size += offset;
        }

        public void clear() {
            size = 0;
        }

        public void put(byte b) {
            data[size++] = b;
        }

        public void put(byte b, int pos) {
            data[pos++] = b;
            size = pos > size ? pos : size;
        }

        public void put(short s) {
            put((byte) s);
            put((byte) (s >>> 8));
        }

        public void put(int i) {
            put((byte) i);
            put((byte) (i >>> 8));
            put((byte) (i >>> 16));
            put((byte) (i >>> 24));
        }

        public void put(byte[] bs) {
            System.arraycopy(bs, 0, data, size, bs.length);
            size += bs.length;
        }
    }

    private final int individualAllocationSize;
    private final Allocation[] singleAllocationReleaseHolder;
    private volatile int allocatedCount;
    private volatile int availableCount;
    private Allocation[] availableAllocations;

    /**
     * Constructs an instance without creating any {@link Allocation}s up front.
     *
     * @param individualAllocationSize The length of each individual {@link Allocation}.
     */
    public SrsAllocator(int individualAllocationSize) {
      this(individualAllocationSize, 0);
    }

    /**
     * Constructs an instance with some {@link Allocation}s created up front.
     * <p>
     *
     * @param individualAllocationSize The length of each individual {@link Allocation}.
     * @param initialAllocationCount The number of allocations to create up front.
     */
    public SrsAllocator(int individualAllocationSize, int initialAllocationCount) {
        this.individualAllocationSize = individualAllocationSize;
        this.availableCount = initialAllocationCount + 10;
        this.availableAllocations = new Allocation[availableCount];
        for (int i = 0; i < availableCount; i++) {
            availableAllocations[i] = new Allocation(individualAllocationSize);
        }
        singleAllocationReleaseHolder = new Allocation[1];
    }

    public synchronized Allocation allocate() {
        allocatedCount++;
        Allocation allocation;
        if (availableCount > 0) {
            allocation = availableAllocations[--availableCount];
            availableAllocations[availableCount] = null;
        } else {
            allocation = new Allocation(individualAllocationSize);
        }
        return allocation;
    }

    public synchronized void release(Allocation allocation) {
        singleAllocationReleaseHolder[0] = allocation;
        release(singleAllocationReleaseHolder);
    }

    public synchronized void release(Allocation[] allocations) {
        if (availableCount + allocations.length >= availableAllocations.length) {
            availableAllocations = Arrays.copyOf(availableAllocations,
                Math.max(availableAllocations.length * 2, availableCount + allocations.length));
        }
        for (Allocation allocation : allocations) {
            allocation.clear();
            availableAllocations[availableCount++] = allocation;
        }
        allocatedCount -= allocations.length;
    }

    public synchronized int getTotalBytesAllocated() {
        return allocatedCount * individualAllocationSize;
    }
}
