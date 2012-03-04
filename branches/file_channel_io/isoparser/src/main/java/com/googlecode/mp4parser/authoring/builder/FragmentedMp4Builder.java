package com.googlecode.mp4parser.authoring.builder;

import com.coremedia.iso.Hex;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.*;
import com.coremedia.iso.boxes.fragment.*;
import com.coremedia.iso.boxes.mdat.MediaDataBoxWithSamples;
import com.coremedia.iso.boxes.mdat.Sample;
import com.googlecode.mp4parser.authoring.DateHelper;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Logger;

/**
 * Creates a fragmented MP4 file.
 */
public class FragmentedMp4Builder implements Mp4Builder {
    FragmentIntersectionFinder intersectionFinder = new SyncSampleIntersectFinderImpl();

    private static final Logger LOG = Logger.getLogger(FragmentedMp4Builder.class.getName());

    public List<String> getAllowedHandlers() {
        return Arrays.asList("soun", "vide");
    }

    public IsoFile build(Movie movie) throws IOException {
        LOG.info("Creating movie " + movie);
        IsoFile isoFile = new IsoFile();
        List<String> minorBrands = new LinkedList<String>();
        minorBrands.add("isom");
        minorBrands.add("iso2");
        minorBrands.add("avc1");

        isoFile.addBox(new FileTypeBox("isom", 0, minorBrands));
        isoFile.addBox(createMovieBox(movie));


        int maxNumberOfFragments = 0;
        for (Track track : movie.getTracks()) {
            int currentLength = intersectionFinder.sampleNumbers(track, movie).length;
            maxNumberOfFragments = currentLength > maxNumberOfFragments ? currentLength : maxNumberOfFragments;
        }

        for (int i = 0; i < maxNumberOfFragments; i++) {
            for (Track track : movie.getTracks()) {
                if (getAllowedHandlers().isEmpty() || getAllowedHandlers().contains(track.getHandler())) {
                    int[] startSamples = intersectionFinder.sampleNumbers(track, movie);

                    if (i < startSamples.length) {
                        int startSample = startSamples[i];

                        int endSample = i + 1 < startSamples.length ? startSamples[i + 1] : track.getSamples().size();

                        if (startSample == endSample) {
                            // empty fragment
                            // just don't add any boxes.
                        } else {
                            isoFile.addBox(createMoof(startSample, endSample, track, i + 1));
                            MediaDataBoxWithSamples mdat = new MediaDataBoxWithSamples();
                            System.err.println("Create mdat from " + startSample + " to " + endSample);
                            for (Sample sample : track.getSamples().subList(startSample, endSample)) {
                                mdat.addSample(sample);
                            }
                            isoFile.addBox(mdat);
                        }

                    } else {
                        //obvious this track has not that many fragments
                    }
                }
            }
        }


        return isoFile;
    }

    public static void dumpHex(ByteBuffer bb) {
        byte[] b = new byte[bb.limit()];
        bb.get(b);
        System.err.println(Hex.encodeHex(b));
        bb.rewind();

    }

    private MovieFragmentBox createMoof(int startSample, int endSample, Track track, int sequenceNumber) {
        List<? extends Sample> samples = track.getSamples().subList(startSample, endSample);

        long[] sampleSizes = new long[samples.size()];
        for (int i = 0; i < sampleSizes.length; i++) {
            sampleSizes[i] = samples.get(i).getSize();
        }


        final TrackFragmentHeaderBox tfhd = new TrackFragmentHeaderBox();
        SampleFlags sf = new SampleFlags();

        tfhd.setDefaultSampleFlags(sf);
        tfhd.setBaseDataOffset(-1);
        MovieFragmentBox moof = new MovieFragmentBox();


        MovieFragmentHeaderBox mfhd = new MovieFragmentHeaderBox();
        moof.addBox(mfhd);
        TrackFragmentBox traf = new TrackFragmentBox();
        moof.addBox(traf);

        traf.addBox(tfhd);
        TrackRunBox trun = new TrackRunBox();
        traf.addBox(trun);

        mfhd.setSequenceNumber(sequenceNumber);
        tfhd.setTrackId(track.getTrackMetaData().getTrackId());

        trun.setSampleDurationPresent(true);
        trun.setSampleSizePresent(true);


        List<TrackRunBox.Entry> entries = new ArrayList<TrackRunBox.Entry>(endSample - startSample);


        Queue<TimeToSampleBox.Entry> timeQueue = new LinkedList<TimeToSampleBox.Entry>(track.getDecodingTimeEntries());
        long durationEntriesLeft = timeQueue.peek().getCount();


        Queue<CompositionTimeToSample.Entry> compositionTimeQueue =
                track.getCompositionTimeEntries() != null && track.getCompositionTimeEntries().size() > 0 ?
                        new LinkedList<CompositionTimeToSample.Entry>(track.getCompositionTimeEntries()) : null;
        long compositionTimeEntriesLeft = compositionTimeQueue != null ? compositionTimeQueue.peek().getCount() : -1;


        if (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty() ||
                track.getSyncSamples() != null && track.getSyncSamples().length != 0) {
            trun.setSampleFlagsPresent(true);
        }

        // SampleFlags firstSampleFlags = new SampleFlags();
        // firstSampleFlags.setSampleIsDifferenceSample(false);

        // trun.setFirstSampleFlags(firstSampleFlags);

        for (int i = 0; i < sampleSizes.length; i++) {
            TrackRunBox.Entry entry = new TrackRunBox.Entry();
            entry.setSampleSize(sampleSizes[i]);
            if (trun.isSampleFlagsPresent()) {
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
                    sflags.setSampleIsDifferenceSample(Arrays.binarySearch(track.getSyncSamples(), startSample + i + 1) < 0);
                }
                // i don't have sample degradation
                entry.setSampleFlags(sflags);

            }

            entry.setSampleDuration(timeQueue.peek().getDelta());
            if (--durationEntriesLeft == 0 && timeQueue.size() > 1) {
                timeQueue.remove();
                durationEntriesLeft = timeQueue.peek().getCount();
            }

            if (compositionTimeQueue != null) {
                trun.setSampleCompositionTimeOffsetPresent(true);
                entry.setSampleCompositionTimeOffset(compositionTimeQueue.peek().getOffset());
                if (--compositionTimeEntriesLeft == 0 && compositionTimeQueue.size() > 1) {
                    compositionTimeQueue.remove();
                    compositionTimeEntriesLeft = compositionTimeQueue.element().getCount();
                }
            }
            entries.add(entry);
        }

        trun.setEntries(entries);
        trun.setDataOffset(1); // dummy to make size correct
        trun.setDataOffset((int) (8 + moof.getSize())); // mdat header + moof size
        return moof;
    }

    private MovieBox createMovieBox(Movie movie) {
        MovieBox movieBox = new MovieBox();
        MovieHeaderBox mvhd = new MovieHeaderBox();
        mvhd.setCreationTime(DateHelper.convert(new Date()));
        mvhd.setModificationTime(DateHelper.convert(new Date()));

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
        movieBox.addBox(mvhd);

        MovieExtendsBox mvex = new MovieExtendsBox();

        for (Track track : movie.getTracks()) {
            // Remove all boxes except the SampleDescriptionBox.

            TrackExtendsBox trex = new TrackExtendsBox();
            trex.setTrackId(track.getTrackMetaData().getTrackId());
            trex.setDefaultSampleDescriptionIndex(1);
            trex.setDefaultSampleDuration(0);
            trex.setDefaultSampleSize(0);
            trex.setDefaultSampleFlags(new SampleFlags());
            // Don't set any good defaults here.
            mvex.addBox(trex);
        }

        movieBox.addBox(mvex);


        for (Track track : movie.getTracks()) {
            movieBox.addBox(createTrackBox(track, movie));
        }
        // metadata here
        return movieBox;

    }

    private TrackBox createTrackBox(Track track, Movie movie) {
        LOG.info("Creating Track " + track);
        TrackBox trackBox = new TrackBox();
        TrackHeaderBox tkhd = new TrackHeaderBox();
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
        tkhd.setCreationTime(DateHelper.convert(track.getTrackMetaData().getCreationTime()));
        // We need to take edit list box into account in trackheader duration
        // but as long as I don't support edit list boxes it is sufficient to
        // just translate media duration to movie timescale
        tkhd.setDuration(getDuration(track) * movie.getTimescale() / track.getTrackMetaData().getTimescale());
        tkhd.setHeight(track.getTrackMetaData().getHeight());
        tkhd.setWidth(track.getTrackMetaData().getWidth());
        tkhd.setLayer(track.getTrackMetaData().getLayer());
        tkhd.setModificationTime(DateHelper.convert(new Date()));
        tkhd.setTrackId(track.getTrackMetaData().getTrackId());
        tkhd.setVolume(track.getTrackMetaData().getVolume());
        trackBox.addBox(tkhd);
        MediaBox mdia = new MediaBox();
        trackBox.addBox(mdia);
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(DateHelper.convert(track.getTrackMetaData().getCreationTime()));
        mdhd.setDuration(getDuration(track));
        mdhd.setTimescale(track.getTrackMetaData().getTimescale());
        mdhd.setLanguage(track.getTrackMetaData().getLanguage());
        mdia.addBox(mdhd);
        HandlerBox hdlr = new HandlerBox();
        mdia.addBox(hdlr);
        hdlr.setHandlerType(track.getHandler());

        MediaInformationBox minf = new MediaInformationBox();
        minf.addBox(track.getMediaHeaderBox());

        // dinf: all these three boxes tell us is that the actual
        // data is in the current file and not somewhere external
        DataInformationBox dinf = new DataInformationBox();
        DataReferenceBox dref = new DataReferenceBox();
        dinf.addBox(dref);
        DataEntryUrlBox url = new DataEntryUrlBox();
        url.setFlags(1);
        dref.addBox(url);
        minf.addBox(dinf);
        //

        SampleTableBox stbl = new SampleTableBox();

        stbl.addBox(track.getSampleDescriptionBox());
        stbl.addBox(new TimeToSampleBox());
        //stbl.addBox(new SampleToChunkBox());
        stbl.addBox(new StaticChunkOffsetBox());

        minf.addBox(stbl);
        mdia.addBox(minf);

        return trackBox;
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
