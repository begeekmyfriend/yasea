package com.coremedia.iso.boxes.mdat;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.*;
import com.coremedia.iso.boxes.fragment.*;

import java.nio.ByteBuffer;
import java.util.*;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * Creates a list of <code>ByteBuffer</code>s that represent the samples of a given track.
 */
public class SampleList extends AbstractList<ByteBuffer> {


    long[] offsets;
    long[] sizes;

    IsoFile isoFile;
    HashMap<MediaDataBox, Long> mdatStartCache = new HashMap<MediaDataBox, Long>();
    HashMap<MediaDataBox, Long> mdatEndCache = new HashMap<MediaDataBox, Long>();
    MediaDataBox[] mdats;

    /**
     * Gets a sorted random access optimized list of all sample offsets.
     * Basically it is a map from sample number to sample offset.
     *
     * @return the sorted list of sample offsets
     */
    public long[] getOffsetKeys() {
        return offsets;
    }


    public SampleList(TrackBox trackBox) {
        initIsoFile(trackBox.getIsoFile()); // where are we?

        // first we get all sample from the 'normal' MP4 part.
        // if there are none - no problem.
        SampleSizeBox sampleSizeBox = trackBox.getSampleTableBox().getSampleSizeBox();
        ChunkOffsetBox chunkOffsetBox = trackBox.getSampleTableBox().getChunkOffsetBox();
        SampleToChunkBox sampleToChunkBox = trackBox.getSampleTableBox().getSampleToChunkBox();


        final long[] chunkOffsets = chunkOffsetBox != null ? chunkOffsetBox.getChunkOffsets() : new long[0];
        if (sampleToChunkBox != null && sampleToChunkBox.getEntries().size() > 0 &&
                chunkOffsets.length > 0 && sampleSizeBox != null && sampleSizeBox.getSampleCount() > 0) {
            long[] numberOfSamplesInChunk = sampleToChunkBox.blowup(chunkOffsets.length);

            int sampleIndex = 0;

            if (sampleSizeBox.getSampleSize() > 0) {
                sizes = new long[l2i(sampleSizeBox.getSampleCount())];
                Arrays.fill(sizes, sampleSizeBox.getSampleSize());
            } else {
                sizes = sampleSizeBox.getSampleSizes();
            }
            offsets = new long[sizes.length];

                for (int i = 0; i < numberOfSamplesInChunk.length; i++) {
                    long thisChunksNumberOfSamples = numberOfSamplesInChunk[i];
                long sampleOffset = chunkOffsets[i];
                    for (int j = 0; j < thisChunksNumberOfSamples; j++) {
                    long sampleSize = sizes[sampleIndex];
                    offsets[sampleIndex] = sampleOffset;
                        sampleOffset += sampleSize;
                        sampleIndex++;
                    }
                }

            }

        // Next we add all samples from the fragments
        // in most cases - I've never seen it different it's either normal or fragmented.        
        List<MovieExtendsBox> movieExtendsBoxes = trackBox.getParent().getBoxes(MovieExtendsBox.class);

        if (movieExtendsBoxes.size() > 0) {
            Map<Long, Long> offsets2Sizes = new HashMap<Long, Long>();
            List<TrackExtendsBox> trackExtendsBoxes = movieExtendsBoxes.get(0).getBoxes(TrackExtendsBox.class);
            for (TrackExtendsBox trackExtendsBox : trackExtendsBoxes) {
                if (trackExtendsBox.getTrackId() == trackBox.getTrackHeaderBox().getTrackId()) {
                    for (MovieFragmentBox movieFragmentBox : trackBox.getIsoFile().getBoxes(MovieFragmentBox.class)) {
                        offsets2Sizes.putAll(getOffsets(movieFragmentBox, trackBox.getTrackHeaderBox().getTrackId(), trackExtendsBox));
                    }
                }
            }
            
            if (sizes == null || offsets == null) {
                sizes = new long[0];
                offsets = new long[0];
            }
            
            splitToArrays(offsets2Sizes);
        }
        
        // We have now a map from all sample offsets to their sizes
    }

    private void splitToArrays(Map<Long, Long> offsets2Sizes) {
        List<Long> keys = new ArrayList<Long>(offsets2Sizes.keySet());
        Collections.sort(keys);

        long[] nuSizes = new long[sizes.length + keys.size()];
        System.arraycopy(sizes, 0, nuSizes, 0, sizes.length);
        long[] nuOffsets = new long[offsets.length + keys.size()];
        System.arraycopy(offsets, 0, nuOffsets, 0, offsets.length);
        for (int i = 0; i < keys.size(); i++) {
            nuOffsets[i + offsets.length] = keys.get(i);
            nuSizes[i + sizes.length] = offsets2Sizes.get(keys.get(i));
        }
        sizes = nuSizes;
        offsets = nuOffsets;
    }
    
    public SampleList(TrackFragmentBox traf) {
        sizes = new long[0];
        offsets = new long[0];
        Map<Long, Long> offsets2Sizes = new HashMap<Long, Long>();
        initIsoFile(traf.getIsoFile());

        final List<MovieFragmentBox> movieFragmentBoxList = isoFile.getBoxes(MovieFragmentBox.class);

        final long trackId = traf.getTrackFragmentHeaderBox().getTrackId();
        for (MovieFragmentBox moof : movieFragmentBoxList) {
            final List<TrackFragmentHeaderBox> trackFragmentHeaderBoxes = moof.getTrackFragmentHeaderBoxes();
            for (TrackFragmentHeaderBox tfhd : trackFragmentHeaderBoxes) {
                if (tfhd.getTrackId() == trackId) {
                    offsets2Sizes.putAll(getOffsets(moof, trackId, null));
                }
            }
        }
        splitToArrays(offsets2Sizes);
    }

    private void initIsoFile(IsoFile isoFile) {
        this.isoFile = isoFile;
        // find all mdats first to be able to use them later with explicitly looking them up
        long currentOffset = 0;
        LinkedList<MediaDataBox> mdats = new LinkedList<MediaDataBox>();
        for (Box b : this.isoFile.getBoxes()) {
            long currentSize = b.getSize();
            if ("mdat".equals(b.getType())) {
                if (b instanceof MediaDataBox) {
                    long contentOffset = currentOffset + ((MediaDataBox) b).getHeader().limit();
                    mdatStartCache.put((MediaDataBox) b, contentOffset);
                    mdatEndCache.put((MediaDataBox) b, contentOffset + currentSize);
                    mdats.add((MediaDataBox) b);
                } else {
                    throw new RuntimeException("Sample need to be in mdats and mdats need to be instanceof MediaDataBox");
                }
            }
            currentOffset += currentSize;
        }
        this.mdats = mdats.toArray(new MediaDataBox[mdats.size()]);
    }


    @Override
    public int size() {
        return sizes.length;
    }


    @Override
    public ByteBuffer get(int index) {
        // it is a two stage lookup: from index to offset to size
        long offset = offsets[index];
        int sampleSize = l2i(sizes[index]);

        for (MediaDataBox mediaDataBox : mdats) {
            long start = mdatStartCache.get(mediaDataBox);
            long end = mdatEndCache.get(mediaDataBox);
            if ((start <= offset) && (offset + sampleSize <= end)) {
                return mediaDataBox.getContent(offset - start, sampleSize);
            }
        }

        throw new RuntimeException("The sample with offset " + offset + " and size " + sampleSize + " is NOT located within an mdat");
    }

    Map<Long, Long> getOffsets(MovieFragmentBox moof, long trackId, TrackExtendsBox trex) {
        Map<Long, Long> offsets2Sizes = new HashMap<Long, Long>();
        List<TrackFragmentBox> traf = moof.getBoxes(TrackFragmentBox.class);
        for (TrackFragmentBox trackFragmentBox : traf) {
            if (trackFragmentBox.getTrackFragmentHeaderBox().getTrackId() == trackId) {
                long baseDataOffset;
                if (trackFragmentBox.getTrackFragmentHeaderBox().hasBaseDataOffset()) {
                    baseDataOffset = trackFragmentBox.getTrackFragmentHeaderBox().getBaseDataOffset();
                } else {
                    baseDataOffset = moof.getOffset();
                }

                for (TrackRunBox trun : trackFragmentBox.getBoxes(TrackRunBox.class)) {
                    long sampleBaseOffset = baseDataOffset + trun.getDataOffset();
                    final TrackFragmentHeaderBox tfhd = ((TrackFragmentBox) trun.getParent()).getTrackFragmentHeaderBox();

                    long offset = 0;
                    for (TrackRunBox.Entry entry : trun.getEntries()) {
                        final long sampleSize;
                        if (trun.isSampleSizePresent()) {
                            sampleSize = entry.getSampleSize();
                            offsets2Sizes.put(offset + sampleBaseOffset, sampleSize);
                            offset += sampleSize;
                        } else {
                            if (tfhd.hasDefaultSampleSize()) {
                                sampleSize = tfhd.getDefaultSampleSize();
                                offsets2Sizes.put(offset + sampleBaseOffset, sampleSize);
                                offset += sampleSize;
                            } else {
                                if (trex == null) {
                                    throw new RuntimeException("File doesn't contain trex box but track fragments aren't fully self contained. Cannot determine sample size.");
                                }
                                sampleSize = trex.getDefaultSampleSize();
                                offsets2Sizes.put(offset + sampleBaseOffset, sampleSize);
                                offset += sampleSize;
                            }
                        }
                    }
                }
            }
        }
        return offsets2Sizes;
    }

}