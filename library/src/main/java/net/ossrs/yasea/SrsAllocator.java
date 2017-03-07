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
    private volatile int availableSentinel;
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
        this.availableSentinel = initialAllocationCount + 10;
        this.availableAllocations = new Allocation[availableSentinel];
        for (int i = 0; i < availableSentinel; i++) {
            availableAllocations[i] = new Allocation(individualAllocationSize);
        }
    }

    public synchronized Allocation allocate(int size) {
        for (int i = 0; i < availableSentinel; i++) {
            if (availableAllocations[i].size() >= size) {
                Allocation ret = availableAllocations[i];
                availableAllocations[i] = null;
                return ret;
            }
        }

        return new Allocation(size > individualAllocationSize ? size : individualAllocationSize);
    }

    public synchronized void release(Allocation allocation) {
        allocation.clear();

        for (int i = 0; i < availableSentinel; i++) {
            if (availableAllocations[i].size() == 0) {
                availableAllocations[i] = allocation;
                return;
            }
        }

        if (availableSentinel + 1 > availableAllocations.length) {
            availableAllocations = Arrays.copyOf(availableAllocations, availableAllocations.length * 2);
        }
        availableAllocations[availableSentinel++] = allocation;
    }
}
