/*
 * Copyright 2012 Sebastian Annies, Hamburg
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.mp4parser.authoring.builder;

import com.coremedia.iso.BoxParser;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.IsoTypeWriter;
import com.coremedia.iso.boxes.*;
import com.coremedia.iso.boxes.fragment.*;
import com.googlecode.mp4parser.authoring.DateHelper;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.logging.Logger;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * Creates a fragmented MP4 file.
 */
public class FragmentedMp4Builder implements Mp4Builder {
    private static final Logger LOG = Logger.getLogger(FragmentedMp4Builder.class.getName());

    protected FragmentIntersectionFinder intersectionFinder;

    public FragmentedMp4Builder() {
        this.intersectionFinder = new SyncSampleIntersectFinderImpl();
    }

    public List<String> getAllowedHandlers() {
        return Arrays.asList("soun", "vide");
    }

    public Box createFtyp(Movie movie) {
        List<String> minorBrands = new LinkedList<String>();
        minorBrands.add("isom");
        minorBrands.add("iso2");
        minorBrands.add("avc1");
        return new FileTypeBox("isom", 0, minorBrands);
    }

    /**
     * Some formats require sorting of the fragments. E.g. Ultraviolet CFF files are required
     * to contain the fragments size sort:
     * <ul>
     * <li>video[1].getBytes().length < audio[1].getBytes().length < subs[1].getBytes().length</li>
     * <li> audio[2].getBytes().length < video[2].getBytes().length < subs[2].getBytes().length</li>
     * </ul>
     *
     * make this fragment:
     *
     * <ol>
     *     <li>video[1]</li>
     *     <li>audio[1]</li>
     *     <li>subs[1]</li>
     *     <li>audio[2]</li>
     *     <li>video[2]</li>
     *     <li>subs[2]</li>
     * </ol>
     *
     * @param tracks the list of tracks to returned sorted
     * @param cycle current fragment (sorting may vary between the fragments)
     * @param intersectionMap a map from tracks to their fragments' first samples.
     * @return the list of tracks in order of appearance in the fragment
     */
    protected List<Track> sortTracksInSequence(List<Track> tracks, final int cycle, final Map<Track, long[]> intersectionMap) {
        tracks = new LinkedList<Track>(tracks);
        Collections.sort(tracks, new Comparator<Track>() {
            public int compare(Track o1, Track o2) {
                long[] startSamples1 = intersectionMap.get(o1);
                long startSample1 = startSamples1[cycle];
                // one based sample numbers - the first sample is 1
                long endSample1 = cycle + 1 < startSamples1.length ? startSamples1[cycle + 1] : o1.getSamples().size() + 1;
                long[] startSamples2 = intersectionMap.get(o2);
                long startSample2 = startSamples2[cycle];
                // one based sample numbers - the first sample is 1
                long endSample2 = cycle + 1 < startSamples2.length ? startSamples2[cycle + 1] : o2.getSamples().size() + 1;
                List<ByteBuffer> samples1 = o1.getSamples().subList(l2i(startSample1) - 1, l2i(endSample1) - 1);
                List<ByteBuffer> samples2 = o2.getSamples().subList(l2i(startSample2) - 1, l2i(endSample2) - 1);
                int size1 = 0;
                for (ByteBuffer byteBuffer : samples1) {
                    size1 += byteBuffer.limit();
                }
                int size2 = 0;
                for (ByteBuffer byteBuffer : samples2) {
                    size2 += byteBuffer.limit();
                }
                return size1 - size2;
            }
        });
        return tracks;
    }

    protected List<Box> createMoofMdat(final Movie movie) {
        List<Box> boxes = new LinkedList<Box>();
        HashMap<Track, long[]> intersectionMap = new HashMap<Track, long[]>();
        int maxNumberOfFragments = 0;
        for (Track track : movie.getTracks()) {
            long[] intersects = intersectionFinder.sampleNumbers(track, movie);
            intersectionMap.put(track, intersects);
            maxNumberOfFragments = Math.max(maxNumberOfFragments, intersects.length);
        }


        int sequence = 1;
        // this loop has two indices:

        for (int cycle = 0; cycle < maxNumberOfFragments; cycle++) {

            final List<Track> sortedTracks = sortTracksInSequence(movie.getTracks(), cycle, intersectionMap);

            for (Track track : sortedTracks) {
                if (getAllowedHandlers().isEmpty() || getAllowedHandlers().contains(track.getHandler())) {
                    long[] startSamples = intersectionMap.get(track);
                    //some tracks may have less fragments -> skip them
                    if (cycle < startSamples.length) {

                        long startSample = startSamples[cycle];
                        // one based sample numbers - the first sample is 1
                        long endSample = cycle + 1 < startSamples.length ? startSamples[cycle + 1] : track.getSamples().size() + 1;

                        // if startSample == endSample the cycle is empty!
                        if (startSample != endSample) {
                            boxes.add(createMoof(startSample, endSample, track, sequence));
                            boxes.add(createMdat(startSample, endSample, track, sequence++));
                        }
                    }
                }
            }
        }
        return boxes;
    }

    /**
     * {@inheritDoc}
     */
    public IsoFile build(Movie movie) {
        LOG.fine("Creating movie " + movie);
        IsoFile isoFile = new IsoFile();


        isoFile.addBox(createFtyp(movie));
        isoFile.addBox(createMoov(movie));

        for (Box box : createMoofMdat(movie)) {
            isoFile.addBox(box);
        }
        isoFile.addBox(createMfra(movie, isoFile));

        return isoFile;
    }

    protected Box createMdat(final long startSample, final long endSample, final Track track, final int i) {

        class Mdat implements Box {
            ContainerBox parent;

            public ContainerBox getParent() {
                return parent;
            }

            public void setParent(ContainerBox parent) {
                this.parent = parent;
            }

            public long getSize() {
                long size = 8; // I don't expect 2gig fragments
                for (ByteBuffer sample : getSamples(startSample, endSample, track, i)) {
                    size += sample.limit();
                }
                return size;
            }

            public String getType() {
                return "mdat";
            }

            public void getBox(WritableByteChannel writableByteChannel) throws IOException {
                List<ByteBuffer> bbs = getSamples(startSample, endSample, track, i);
                final List<ByteBuffer> samples = ByteBufferHelper.mergeAdjacentBuffers(bbs);
                ByteBuffer header = ByteBuffer.allocate(8);
                IsoTypeWriter.writeUInt32(header, l2i(getSize()));
                header.put(IsoFile.fourCCtoBytes(getType()));
                header.rewind();
                writableByteChannel.write(header);
                if (writableByteChannel instanceof GatheringByteChannel) {

                    int STEPSIZE = 1024;
                    // This is required to prevent android from crashing
                    // it seems that {@link GatheringByteChannel#write(java.nio.ByteBuffer[])}
                    // just handles up to 1024 buffers
                    for (int i = 0; i < Math.ceil((double) samples.size() / STEPSIZE); i++) {
                        List<ByteBuffer> sublist = samples.subList(
                                i * STEPSIZE, // start
                                (i + 1) * STEPSIZE < samples.size() ? (i + 1) * STEPSIZE : samples.size()); // end
                        ByteBuffer sampleArray[] = sublist.toArray(new ByteBuffer[sublist.size()]);
                        do {
                            ((GatheringByteChannel) writableByteChannel).write(sampleArray);
                        } while (sampleArray[sampleArray.length - 1].remaining() > 0);
                    }
                    //System.err.println(bytesWritten);
                } else {
                    for (ByteBuffer sample : samples) {
                        sample.rewind();
                        writableByteChannel.write(sample);
                    }
                }

            }

            public void parse(ReadableByteChannel readableByteChannel, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {

            }
        }

        return new Mdat();
    }

    protected Box createTfhd(long startSample, long endSample, Track track, int sequenceNumber) {
        TrackFragmentHeaderBox tfhd = new TrackFragmentHeaderBox();
        SampleFlags sf = new SampleFlags();

        tfhd.setDefaultSampleFlags(sf);
        tfhd.setBaseDataOffset(-1);
        tfhd.setTrackId(track.getTrackMetaData().getTrackId());
        return tfhd;
    }

    protected Box createMfhd(long startSample, long endSample, Track track, int sequenceNumber) {
        MovieFragmentHeaderBox mfhd = new MovieFragmentHeaderBox();
        mfhd.setSequenceNumber(sequenceNumber);
        return mfhd;
    }

    protected Box createTraf(long startSample, long endSample, Track track, int sequenceNumber) {
        TrackFragmentBox traf = new TrackFragmentBox();
        traf.addBox(createTfhd(startSample, endSample, track, sequenceNumber));
        for (Box trun : createTruns(startSample, endSample, track, sequenceNumber)) {
            traf.addBox(trun);
        }

        return traf;
    }


    /**
     * Gets the all samples starting with <code>startSample</code> (one based -> one is the first) and
     * ending with <code>endSample</code> (exclusive).
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return a <code>List&lt;ByteBuffer></code> of raw samples
     */
    protected List<ByteBuffer> getSamples(long startSample, long endSample, Track track, int sequenceNumber) {
        // since startSample and endSample are one-based substract 1 before addressing list elements
        return track.getSamples().subList(l2i(startSample) - 1, l2i(endSample) - 1);
    }

    /**
     * Gets the sizes of a sequence of samples-
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return
     */
    protected long[] getSampleSizes(long startSample, long endSample, Track track, int sequenceNumber) {
        List<ByteBuffer> samples = getSamples(startSample, endSample, track, sequenceNumber);

        long[] sampleSizes = new long[samples.size()];
        for (int i = 0; i < sampleSizes.length; i++) {
            sampleSizes[i] = samples.get(i).limit();
        }
        return sampleSizes;
    }

    /**
     * Creates one or more track run boxes for a given sequence.
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return the list of TrackRun boxes.
     */
    protected List<? extends Box> createTruns(long startSample, long endSample, Track track, int sequenceNumber) {
        TrackRunBox trun = new TrackRunBox();
        long[] sampleSizes = getSampleSizes(startSample, endSample, track, sequenceNumber);

        trun.setSampleDurationPresent(true);
        trun.setSampleSizePresent(true);
        List<TrackRunBox.Entry> entries = new ArrayList<TrackRunBox.Entry>(l2i(endSample - startSample));


        Queue<TimeToSampleBox.Entry> timeQueue = new LinkedList<TimeToSampleBox.Entry>(track.getDecodingTimeEntries());
        long left = startSample - 1;
        long curEntryLeft = timeQueue.peek().getCount();
        while (left > curEntryLeft) {
            left -= curEntryLeft;
            timeQueue.remove();
            curEntryLeft = timeQueue.peek().getCount();
        }
        curEntryLeft -= left;


        Queue<CompositionTimeToSample.Entry> compositionTimeQueue =
                track.getCompositionTimeEntries() != null && track.getCompositionTimeEntries().size() > 0 ?
                        new LinkedList<CompositionTimeToSample.Entry>(track.getCompositionTimeEntries()) : null;
        long compositionTimeEntriesLeft = compositionTimeQueue != null ? compositionTimeQueue.peek().getCount() : -1;


        trun.setSampleCompositionTimeOffsetPresent(compositionTimeEntriesLeft > 0);

        // fast forward composition stuff
        for (long i = 1; i < startSample; i++) {
            if (compositionTimeQueue != null) {
                //trun.setSampleCompositionTimeOffsetPresent(true);
                if (--compositionTimeEntriesLeft == 0 && compositionTimeQueue.size() > 1) {
                    compositionTimeQueue.remove();
                    compositionTimeEntriesLeft = compositionTimeQueue.element().getCount();
                }
            }
        }

        boolean sampleFlagsRequired = (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty() ||
                track.getSyncSamples() != null && track.getSyncSamples().length != 0);

        trun.setSampleFlagsPresent(sampleFlagsRequired);

        for (int i = 0; i < sampleSizes.length; i++) {
            TrackRunBox.Entry entry = new TrackRunBox.Entry();
            entry.setSampleSize(sampleSizes[i]);
            if (sampleFlagsRequired) {
                //if (false) {
                SampleFlags sflags = new SampleFlags();

                if (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty()) {
                    SampleDependencyTypeBox.Entry e = track.getSampleDependencies().get(i);
                    sflags.setSampleDependsOn(e.getSampleDependsOn());
                    sflags.setSampleIsDependedOn(e.getSampleIsDependentOn());
                    sflags.setSampleHasRedundancy(e.getSampleHasRedundancy());
                }
                if (track.getSyncSamples() != null && track.getSyncSamples().length > 0) {
                    // we have to mark non-sync samples!
                    if (Arrays.binarySearch(track.getSyncSamples(), startSample + i) >= 0) {
                        sflags.setSampleIsDifferenceSample(false);
                        sflags.setSampleDependsOn(2);
                    } else {
                        sflags.setSampleIsDifferenceSample(true);
                        sflags.setSampleDependsOn(1);
                    }
                }
                // i don't have sample degradation
                entry.setSampleFlags(sflags);

            }

            entry.setSampleDuration(timeQueue.peek().getDelta());
            if (--curEntryLeft == 0 && timeQueue.size() > 1) {
                timeQueue.remove();
                curEntryLeft = timeQueue.peek().getCount();
            }

            if (compositionTimeQueue != null) {
                entry.setSampleCompositionTimeOffset(compositionTimeQueue.peek().getOffset());
                if (--compositionTimeEntriesLeft == 0 && compositionTimeQueue.size() > 1) {
                    compositionTimeQueue.remove();
                    compositionTimeEntriesLeft = compositionTimeQueue.element().getCount();
                }
            }
            entries.add(entry);
        }

        trun.setEntries(entries);

        return Collections.singletonList(trun);
    }

    /**
     * Creates a 'moof' box for a given sequence of samples.
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return the list of TrackRun boxes.
     */
    protected Box createMoof(long startSample, long endSample, Track track, int sequenceNumber) {
        MovieFragmentBox moof = new MovieFragmentBox();
        moof.addBox(createMfhd(startSample, endSample, track, sequenceNumber));
        moof.addBox(createTraf(startSample, endSample, track, sequenceNumber));

        TrackRunBox firstTrun = moof.getTrackRunBoxes().get(0);
        firstTrun.setDataOffset(1); // dummy to make size correct
        firstTrun.setDataOffset((int) (8 + moof.getSize())); // mdat header + moof size

        return moof;
    }

    /**
     * Creates a single 'mvhd' movie header box for a given movie.
     *
     * @param movie the concerned movie
     * @return an 'mvhd' box
     */
    protected Box createMvhd(Movie movie) {
        MovieHeaderBox mvhd = new MovieHeaderBox();
        mvhd.setVersion(1);
        mvhd.setCreationTime(new Date());
        mvhd.setModificationTime(new Date());
        long movieTimeScale = movie.getTimescale();
        long duration = 0;

        for (Track track : movie.getTracks()) {
            long tracksDuration = getDuration(track) * movieTimeScale / track.getTrackMetaData().getTimescale();
            if (tracksDuration > duration) {
                duration = tracksDuration;
            }


        }

        mvhd.setDuration(duration);
        mvhd.setTimescale(movieTimeScale);
        // find the next available trackId
        long nextTrackId = 0;
        for (Track track : movie.getTracks()) {
            nextTrackId = nextTrackId < track.getTrackMetaData().getTrackId() ? track.getTrackMetaData().getTrackId() : nextTrackId;
        }
        mvhd.setNextTrackId(++nextTrackId);
        return mvhd;
    }

    /**
     * Creates a fully populated 'moov' box with all child boxes. Child boxes are:
     * <ul>
     * <li>{@link #createMvhd(com.googlecode.mp4parser.authoring.Movie) mvhd}</li>
     * <li>{@link #createMvex(com.googlecode.mp4parser.authoring.Movie)  mvex}</li>
     * <li>a {@link #createTrak(com.googlecode.mp4parser.authoring.Track, com.googlecode.mp4parser.authoring.Movie)  trak} for every track</li>
     * </ul>
     *
     * @param movie the concerned movie
     * @return fully populated 'moov'
     */
    protected Box createMoov(Movie movie) {
        MovieBox movieBox = new MovieBox();

        movieBox.addBox(createMvhd(movie));
        movieBox.addBox(createMvex(movie));

        for (Track track : movie.getTracks()) {
            movieBox.addBox(createTrak(track, movie));
        }
        // metadata here
        return movieBox;

    }

    /**
     * Creates a 'tfra' - track fragment random access box for the given track with the isoFile.
     * The tfra contains a map of random access points with time as key and offset within the isofile
     * as value.
     *
     * @param track   the concerned track
     * @param isoFile the track is contained in
     * @return a track fragment random access box.
     */
    protected Box createTfra(Track track, IsoFile isoFile) {
        TrackFragmentRandomAccessBox tfra = new TrackFragmentRandomAccessBox();
        tfra.setVersion(1); // use long offsets and times
        List<TrackFragmentRandomAccessBox.Entry> offset2timeEntries = new LinkedList<TrackFragmentRandomAccessBox.Entry>();
        List<Box> boxes = isoFile.getBoxes();
        long offset = 0;
        long duration = 0;
        for (Box box : boxes) {
            if (box instanceof MovieFragmentBox) {
                List<TrackFragmentBox> trafs = ((MovieFragmentBox) box).getBoxes(TrackFragmentBox.class);
                for (int i = 0; i < trafs.size(); i++) {
                    TrackFragmentBox traf = trafs.get(i);
                    if (traf.getTrackFragmentHeaderBox().getTrackId() == track.getTrackMetaData().getTrackId()) {
                        // here we are at the offset required for the current entry.
                        List<TrackRunBox> truns = traf.getBoxes(TrackRunBox.class);
                        for (int j = 0; j < truns.size(); j++) {
                            List<TrackFragmentRandomAccessBox.Entry> offset2timeEntriesThisTrun = new LinkedList<TrackFragmentRandomAccessBox.Entry>();
                            TrackRunBox trun = truns.get(j);
                            for (int k = 0; k < trun.getEntries().size(); k++) {
                                TrackRunBox.Entry trunEntry = trun.getEntries().get(k);
                                SampleFlags sf = null;
                                if (k == 0 && trun.isFirstSampleFlagsPresent()) {
                                    sf = trun.getFirstSampleFlags();
                                } else if (trun.isSampleFlagsPresent()) {
                                    sf = trunEntry.getSampleFlags();
                                } else {
                                    List<MovieExtendsBox> mvexs = isoFile.getMovieBox().getBoxes(MovieExtendsBox.class);
                                    for (MovieExtendsBox mvex : mvexs) {
                                        List<TrackExtendsBox> trexs = mvex.getBoxes(TrackExtendsBox.class);
                                        for (TrackExtendsBox trex : trexs) {
                                            if (trex.getTrackId() == track.getTrackMetaData().getTrackId()) {
                                                sf = trex.getDefaultSampleFlags();
                                            }
                                        }
                                    }

                                }
                                if (sf == null) {
                                    throw new RuntimeException("Could not find any SampleFlags to indicate random access or not");
                                }
                                if (sf.getSampleDependsOn() == 2) {
                                    offset2timeEntriesThisTrun.add(new TrackFragmentRandomAccessBox.Entry(
                                            duration,
                                            offset,
                                            i + 1, j + 1, k + 1));
                                }
                                duration += trunEntry.getSampleDuration();
                            }
                            if (offset2timeEntriesThisTrun.size() == trun.getEntries().size() && trun.getEntries().size() > 0) {
                                // Oooops every sample seems to be random access sample
                                // is this an audio track? I don't care.
                                // I just use the first for trun sample for tfra random access
                                offset2timeEntries.add(offset2timeEntriesThisTrun.get(0));
                            } else {
                                offset2timeEntries.addAll(offset2timeEntriesThisTrun);
                            }
                        }
                    }
                }
            }


            offset += box.getSize();
        }
        tfra.setEntries(offset2timeEntries);
        tfra.setTrackId(track.getTrackMetaData().getTrackId());
        return tfra;
    }

    /**
     * Creates a 'mfra' - movie fragment random access box for the given movie in the given
     * isofile. Uses {@link #createTfra(com.googlecode.mp4parser.authoring.Track, com.coremedia.iso.IsoFile)}
     * to generate the child boxes.
     *
     * @param movie   concerned movie
     * @param isoFile concerned isofile
     * @return a complete 'mfra' box
     */
    protected Box createMfra(Movie movie, IsoFile isoFile) {
        MovieFragmentRandomAccessBox mfra = new MovieFragmentRandomAccessBox();
        for (Track track : movie.getTracks()) {
            mfra.addBox(createTfra(track, isoFile));
        }

        MovieFragmentRandomAccessOffsetBox mfro = new MovieFragmentRandomAccessOffsetBox();
        mfra.addBox(mfro);
        mfro.setMfraSize(mfra.getSize());
        return mfra;
    }

    protected Box createTrex(Movie movie, Track track) {
        TrackExtendsBox trex = new TrackExtendsBox();
        trex.setTrackId(track.getTrackMetaData().getTrackId());
        trex.setDefaultSampleDescriptionIndex(1);
        trex.setDefaultSampleDuration(0);
        trex.setDefaultSampleSize(0);
        SampleFlags sf = new SampleFlags();
        if ("soun".equals(track.getHandler())) {
            // as far as I know there is no audio encoding
            // where the sample are not self contained.
            sf.setSampleDependsOn(2);
            sf.setSampleIsDependedOn(2);
        }
        trex.setDefaultSampleFlags(sf);
        return trex;
    }

    /**
     * Creates a 'mvex' - movie extends box and populates it with 'trex' boxes
     * by calling {@link #createTrex(com.googlecode.mp4parser.authoring.Movie, com.googlecode.mp4parser.authoring.Track)}
     * for each track to generate them
     *
     * @param movie the source movie
     * @return a complete 'mvex'
     */
    protected Box createMvex(Movie movie) {
        MovieExtendsBox mvex = new MovieExtendsBox();
        final MovieExtendsHeaderBox mved = new MovieExtendsHeaderBox();
        for (Track track : movie.getTracks()) {
            final long trackDuration = getTrackDuration(movie, track);
            if (mved.getFragmentDuration() < trackDuration) {
                mved.setFragmentDuration(trackDuration);
            }
        }
        mvex.addBox(mved);

        for (Track track : movie.getTracks()) {
            mvex.addBox(createTrex(movie, track));
        }
        return mvex;
    }

    protected Box createTkhd(Movie movie, Track track) {
        TrackHeaderBox tkhd = new TrackHeaderBox();
        tkhd.setVersion(1);
        int flags = 0;
        if (track.isEnabled()) {
            flags += 1;
        }

        if (track.isInMovie()) {
            flags += 2;
        }

        if (track.isInPreview()) {
            flags += 4;
        }

        if (track.isInPoster()) {
            flags += 8;
        }
        tkhd.setFlags(flags);

        tkhd.setAlternateGroup(track.getTrackMetaData().getGroup());
        tkhd.setCreationTime(track.getTrackMetaData().getCreationTime());
        // We need to take edit list box into account in trackheader duration
        // but as long as I don't support edit list boxes it is sufficient to
        // just translate media duration to movie timescale
        tkhd.setDuration(getTrackDuration(movie, track));
        tkhd.setHeight(track.getTrackMetaData().getHeight());
        tkhd.setWidth(track.getTrackMetaData().getWidth());
        tkhd.setLayer(track.getTrackMetaData().getLayer());
        tkhd.setModificationTime(new Date());
        tkhd.setTrackId(track.getTrackMetaData().getTrackId());
        tkhd.setVolume(track.getTrackMetaData().getVolume());
        return tkhd;
    }

    private long getTrackDuration(Movie movie, Track track) {
        return getDuration(track) * movie.getTimescale() / track.getTrackMetaData().getTimescale();
    }

    protected Box createMdhd(Movie movie, Track track) {
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(track.getTrackMetaData().getCreationTime());
        mdhd.setDuration(getDuration(track));
        mdhd.setTimescale(track.getTrackMetaData().getTimescale());
        mdhd.setLanguage(track.getTrackMetaData().getLanguage());
        return mdhd;
    }

    protected Box createStbl(Movie movie, Track track) {
        SampleTableBox stbl = new SampleTableBox();

        stbl.addBox(track.getSampleDescriptionBox());
        stbl.addBox(new TimeToSampleBox());
        //stbl.addBox(new SampleToChunkBox());
        stbl.addBox(new StaticChunkOffsetBox());
        return stbl;
    }

    protected Box createMinf(Track track, Movie movie) {
        MediaInformationBox minf = new MediaInformationBox();
        minf.addBox(track.getMediaHeaderBox());
        minf.addBox(createDinf(movie, track));
        minf.addBox(createStbl(movie, track));
        return minf;
    }

    protected Box createMdiaHdlr(Track track, Movie movie) {
        HandlerBox hdlr = new HandlerBox();
        hdlr.setHandlerType(track.getHandler());
        return hdlr;
    }

    protected Box createMdia(Track track, Movie movie) {
        MediaBox mdia = new MediaBox();
        mdia.addBox(createMdhd(movie, track));


        mdia.addBox(createMdiaHdlr(track, movie));


        mdia.addBox(createMinf(track, movie));
        return mdia;
    }

    protected Box createTrak(Track track, Movie movie) {
        LOG.fine("Creating Track " + track);
        TrackBox trackBox = new TrackBox();
        trackBox.addBox(createTkhd(movie, track));
        trackBox.addBox(createMdia(track, movie));
        return trackBox;
    }

    protected DataInformationBox createDinf(Movie movie, Track track) {
        DataInformationBox dinf = new DataInformationBox();
        DataReferenceBox dref = new DataReferenceBox();
        dinf.addBox(dref);
        DataEntryUrlBox url = new DataEntryUrlBox();
        url.setFlags(1);
        dref.addBox(url);
        return dinf;
    }

    public FragmentIntersectionFinder getFragmentIntersectionFinder() {
        return intersectionFinder;
    }

    public void setIntersectionFinder(FragmentIntersectionFinder intersectionFinder) {
        this.intersectionFinder = intersectionFinder;
    }

    protected long getDuration(Track track) {
        long duration = 0;
        for (TimeToSampleBox.Entry entry : track.getDecodingTimeEntries()) {
            duration += entry.getCount() * entry.getDelta();
        }
        return duration;
    }


}
