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

import com.coremedia.iso.boxes.TimeToSampleBox;
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static com.googlecode.mp4parser.util.Math.lcm;

/**
 * This <code>FragmentIntersectionFinder</code> cuts the input movie video tracks in
 * fragments of the same length exactly before the sync samples. Audio tracks are cut
 * into pieces of similar length.
 */
public class SyncSampleIntersectFinderImpl implements FragmentIntersectionFinder {

    private static Logger LOG = Logger.getLogger(SyncSampleIntersectFinderImpl.class.getName());
    private static Map<CacheTuple, long[]> getTimesCache = new ConcurrentHashMap<CacheTuple, long[]>();
    private static Map<CacheTuple, long[]> getSampleNumbersCache = new ConcurrentHashMap<CacheTuple, long[]>();

    private final int minFragmentDurationSeconds;

    public SyncSampleIntersectFinderImpl() {
        minFragmentDurationSeconds = 0;
    }

    /**
     * Creates a <code>SyncSampleIntersectFinderImpl</code> that will not create any fragment
     * smaller than the given <code>minFragmentDurationSeconds</code>
     *
     * @param minFragmentDurationSeconds the smallest allowable duration of a fragment.
     */
    public SyncSampleIntersectFinderImpl(int minFragmentDurationSeconds) {
        this.minFragmentDurationSeconds = minFragmentDurationSeconds;
    }

    /**
     * Gets an array of sample numbers that are meant to be the first sample of each
     * chunk or fragment.
     *
     * @param track concerned track
     * @param movie the context of the track
     * @return an array containing the ordinal of each fragment's first sample
     */
    public long[] sampleNumbers(Track track, Movie movie) {
        final CacheTuple key = new CacheTuple(track, movie);
        final long[] result = getSampleNumbersCache.get(key);
        if (result != null) {
            return result;
        }

        if ("vide".equals(track.getHandler())) {
            if (track.getSyncSamples() != null && track.getSyncSamples().length > 0) {
                List<long[]> times = getSyncSamplesTimestamps(movie, track);
                final long[] commonIndices = getCommonIndices(track.getSyncSamples(), getTimes(track, movie), track.getTrackMetaData().getTimescale(), times.toArray(new long[times.size()][]));
                getSampleNumbersCache.put(key, commonIndices);
                return commonIndices;
            } else {
                throw new RuntimeException("Video Tracks need sync samples. Only tracks other than video may have no sync samples.");
            }
        } else if ("soun".equals(track.getHandler())) {
            Track referenceTrack = null;
            for (Track candidate : movie.getTracks()) {
                if (candidate.getSyncSamples() != null && "vide".equals(candidate.getHandler()) && candidate.getSyncSamples().length > 0) {
                    referenceTrack = candidate;
                }
            }
            if (referenceTrack != null) {

                // Gets the reference track's fra
                long[] refSyncSamples = sampleNumbers(referenceTrack, movie);

                int refSampleCount = referenceTrack.getSamples().size();

                long[] syncSamples = new long[refSyncSamples.length];
                long minSampleRate = 192000;
                for (Track testTrack : movie.getTracks()) {
                    if ("soun".equals(testTrack.getHandler())) {
                        AudioSampleEntry ase = (AudioSampleEntry) testTrack.getSampleDescriptionBox().getSampleEntry();
                        if (ase.getSampleRate() < minSampleRate) {
                            minSampleRate = ase.getSampleRate();
                            long sc = testTrack.getSamples().size();
                            double stretch = (double) sc / refSampleCount;
                            TimeToSampleBox.Entry sttsEntry = testTrack.getDecodingTimeEntries().get(0);
                            long samplesPerFrame = sttsEntry.getDelta(); // Assuming all audio tracks have the same number of samples per frame, which they do for all known types

                            for (int i = 0; i < syncSamples.length; i++) {
                                long start = (long) Math.ceil(stretch * (refSyncSamples[i] - 1) * samplesPerFrame);
                                syncSamples[i] = start;
                                // The Stretch makes sure that there are as much audio and video chunks!
                            }
                            break;
                        }
                    }
                }
                AudioSampleEntry ase = (AudioSampleEntry) track.getSampleDescriptionBox().getSampleEntry();
                TimeToSampleBox.Entry sttsEntry = track.getDecodingTimeEntries().get(0);
                long samplesPerFrame = sttsEntry.getDelta(); // Assuming all audio tracks have the same number of samples per frame, which they do for all known types
                double factor = (double) ase.getSampleRate() / (double) minSampleRate;
                if (factor != Math.rint(factor)) { // Not an integer
                    throw new RuntimeException("Sample rates must be a multiple of the lowest sample rate to create a correct file!");
                }
                for (int i = 0; i < syncSamples.length; i++) {
                    syncSamples[i] = (long) (1 + syncSamples[i] * factor / (double) samplesPerFrame);
                }
                getSampleNumbersCache.put(key, syncSamples);
                return syncSamples;
            }
            throw new RuntimeException("There was absolutely no Track with sync samples. I can't work with that!");
        } else {
            // Ok, my track has no sync samples - let's find one with sync samples.
            for (Track candidate : movie.getTracks()) {
                if (candidate.getSyncSamples() != null && candidate.getSyncSamples().length > 0) {
                    long[] refSyncSamples = sampleNumbers(candidate, movie);
                    int refSampleCount = candidate.getSamples().size();

                    long[] syncSamples = new long[refSyncSamples.length];
                    long sc = track.getSamples().size();
                    double stretch = (double) sc / refSampleCount;

                    for (int i = 0; i < syncSamples.length; i++) {
                        long start = (long) Math.ceil(stretch * (refSyncSamples[i] - 1)) + 1;
                        syncSamples[i] = start;
                        // The Stretch makes sure that there are as much audio and video chunks!
                    }
                    getSampleNumbersCache.put(key, syncSamples);
                    return syncSamples;
                }
            }
            throw new RuntimeException("There was absolutely no Track with sync samples. I can't work with that!");
        }


    }

    /**
     * Calculates the timestamp of all tracks' sync samples.
     *
     * @param movie
     * @param track
     * @return
     */
    public static List<long[]> getSyncSamplesTimestamps(Movie movie, Track track) {
        List<long[]> times = new LinkedList<long[]>();
        for (Track currentTrack : movie.getTracks()) {
            if (currentTrack.getHandler().equals(track.getHandler())) {
                long[] currentTrackSyncSamples = currentTrack.getSyncSamples();
                if (currentTrackSyncSamples != null && currentTrackSyncSamples.length > 0) {
                    final long[] currentTrackTimes = getTimes(currentTrack, movie);
                    times.add(currentTrackTimes);
                }
            }
        }
        return times;
    }

    public long[] getCommonIndices(long[] syncSamples, long[] syncSampleTimes, long timeScale, long[]... otherTracksTimes) {
        List<Long> nuSyncSamples = new LinkedList<Long>();
        List<Long> nuSyncSampleTimes = new LinkedList<Long>();


        for (int i = 0; i < syncSampleTimes.length; i++) {
            boolean foundInEveryRef = true;
            for (long[] times : otherTracksTimes) {
                foundInEveryRef &= (Arrays.binarySearch(times, syncSampleTimes[i]) >= 0);
            }

            if (foundInEveryRef) {
                // use sample only if found in every other track.
                nuSyncSamples.add(syncSamples[i]);
                nuSyncSampleTimes.add(syncSampleTimes[i]);
            }
        }
        // We have two arrays now:
        // nuSyncSamples: Contains all common sync samples
        // nuSyncSampleTimes: Contains the times of all sync samples

        // Start: Warn user if samples are not matching!
        if (nuSyncSamples.size() < (syncSamples.length * 0.25)) {
            String log = "";
            log += String.format("%5d - Common:  [", nuSyncSamples.size());
            for (long l : nuSyncSamples) {
                log += (String.format("%10d,", l));
            }
            log += ("]");
            LOG.warning(log);
            log = "";

            log += String.format("%5d - In    :  [", syncSamples.length);
            for (long l : syncSamples) {
                log += (String.format("%10d,", l));
            }
            log += ("]");
            LOG.warning(log);
            LOG.warning("There are less than 25% of common sync samples in the given track.");
            throw new RuntimeException("There are less than 25% of common sync samples in the given track.");
        } else if (nuSyncSamples.size() < (syncSamples.length * 0.5)) {
            LOG.fine("There are less than 50% of common sync samples in the given track. This is implausible but I'm ok to continue.");
        } else if (nuSyncSamples.size() < syncSamples.length) {
            LOG.finest("Common SyncSample positions vs. this tracks SyncSample positions: " + nuSyncSamples.size() + " vs. " + syncSamples.length);
        }
        // End: Warn user if samples are not matching!




        List<Long> finalSampleList = new LinkedList<Long>();

        if (minFragmentDurationSeconds > 0) {
            // if minFragmentDurationSeconds is greater 0
            // we need to throw away certain samples.
            long lastSyncSampleTime = -1;
            Iterator<Long> nuSyncSamplesIterator = nuSyncSamples.iterator();
            Iterator<Long> nuSyncSampleTimesIterator = nuSyncSampleTimes.iterator();
            while (nuSyncSamplesIterator.hasNext() && nuSyncSampleTimesIterator.hasNext()) {
                long curSyncSample = nuSyncSamplesIterator.next();
                long curSyncSampleTime = nuSyncSampleTimesIterator.next();
                if (lastSyncSampleTime == -1 || (curSyncSampleTime - lastSyncSampleTime) / timeScale >= minFragmentDurationSeconds) {
                    finalSampleList.add(curSyncSample);
                    lastSyncSampleTime = curSyncSampleTime;
                }
            }
        } else {
            // the list of all samples is the final list of samples
            // since minFragmentDurationSeconds ist not used.
            finalSampleList = nuSyncSamples;
        }


        // transform the list to an array
        long[] finalSampleArray = new long[finalSampleList.size()];
        for (int i = 0; i < finalSampleArray.length; i++) {
            finalSampleArray[i] = finalSampleList.get(i);
        }
        return finalSampleArray;

    }


    private static long[] getTimes(Track track, Movie m) {
        final CacheTuple key = new CacheTuple(track, m);
        final long[] result = getTimesCache.get(key);
        if (result != null) {
            return result;
        }

        long[] syncSamples = track.getSyncSamples();
        long[] syncSampleTimes = new long[syncSamples.length];
        Queue<TimeToSampleBox.Entry> timeQueue = new LinkedList<TimeToSampleBox.Entry>(track.getDecodingTimeEntries());

        int currentSample = 1;  // first syncsample is 1
        long currentDuration = 0;
        long currentDelta = 0;
        int currentSyncSampleIndex = 0;
        long left = 0;

        final long scalingFactor = calculateTracktimesScalingFactor(m, track);

        while (currentSample <= syncSamples[syncSamples.length - 1]) {
            if (currentSample++ == syncSamples[currentSyncSampleIndex]) {
                syncSampleTimes[currentSyncSampleIndex++] = currentDuration * scalingFactor;
            }
            if (left-- == 0) {
                TimeToSampleBox.Entry entry = timeQueue.poll();
                left = entry.getCount() - 1;
                currentDelta = entry.getDelta();
            }
            currentDuration += currentDelta;
        }
        getTimesCache.put(key, syncSampleTimes);
        return syncSampleTimes;
    }

    private static long calculateTracktimesScalingFactor(Movie m, Track track) {
        long timeScale = 1;
        for (Track track1 : m.getTracks()) {
            if (track1.getHandler().equals(track.getHandler())) {
                if (track1.getTrackMetaData().getTimescale() != track.getTrackMetaData().getTimescale()) {
                    timeScale = lcm(timeScale, track1.getTrackMetaData().getTimescale());
                }
            }
        }
        return timeScale;
    }

    public static class CacheTuple {
        Track track;
        Movie movie;

        public CacheTuple(Track track, Movie movie) {
            this.track = track;
            this.movie = movie;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheTuple that = (CacheTuple) o;

            if (movie != null ? !movie.equals(that.movie) : that.movie != null) return false;
            if (track != null ? !track.equals(that.track) : that.track != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = track != null ? track.hashCode() : 0;
            result = 31 * result + (movie != null ? movie.hashCode() : 0);
            return result;
        }
    }
}
