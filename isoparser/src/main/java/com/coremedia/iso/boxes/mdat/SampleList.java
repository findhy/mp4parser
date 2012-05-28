package com.coremedia.iso.boxes.mdat;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.ChunkOffsetBox;
import com.coremedia.iso.boxes.SampleSizeBox;
import com.coremedia.iso.boxes.SampleToChunkBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.fragment.MovieExtendsBox;
import com.coremedia.iso.boxes.fragment.MovieFragmentBox;
import com.coremedia.iso.boxes.fragment.TrackExtendsBox;
import com.coremedia.iso.boxes.fragment.TrackFragmentBox;
import com.coremedia.iso.boxes.fragment.TrackFragmentHeaderBox;
import com.coremedia.iso.boxes.fragment.TrackRunBox;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * Creates a list of <code>ByteBuffer</code>s that represent the samples of a given track.
 */
public class SampleList extends AbstractList<ByteBuffer> {

    Map<Long, Long> offsets2Sizes;
    List<Long> offsetKeys = null;
    IsoFile isoFile;
    HashMap<MediaDataBox, Long> mdatStartCache = new HashMap<MediaDataBox, Long>();
    HashMap<MediaDataBox, Long> mdatEndCache = new HashMap<MediaDataBox, Long>();
    ArrayList<MediaDataBox> mdats = new ArrayList<MediaDataBox>(1);

    /**
     * Gets a sorted random access optimized list of all sample offsets.
     * Basically it is a map from sample number to sample offset.
     *
     * @return the sorted list of sample offsets
     */
    public List<Long> getOffsetKeys() {
        if (offsetKeys == null) {
            List<Long> offsetKeys = new ArrayList<Long>(offsets2Sizes.size());
            for (Long aLong : offsets2Sizes.keySet()) {
                offsetKeys.add(aLong);
            }
            Collections.sort(offsetKeys);
            this.offsetKeys = offsetKeys;
        }
        return offsetKeys;
    }


    public SampleList(TrackBox trackBox) {
        this.isoFile = trackBox.getIsoFile(); // where are we?
        offsets2Sizes = new HashMap<Long, Long>();

        // find all mdats first to be able to use them later with explicitly looking them up
        long currentOffset = 0;
        for (Box b : isoFile.getBoxes()) {
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


        // first we get all sample from the 'normal' MP4 part.
        // if there are none - no problem.

        SampleSizeBox sampleSizeBox = trackBox.getSampleTableBox().getSampleSizeBox();
        ChunkOffsetBox chunkOffsetBox = trackBox.getSampleTableBox().getChunkOffsetBox();
        SampleToChunkBox sampleToChunkBox = trackBox.getSampleTableBox().getSampleToChunkBox();


        if (sampleToChunkBox != null && sampleToChunkBox.getEntries().size() > 0 && chunkOffsetBox != null &&
                chunkOffsetBox.getChunkOffsets().length > 0 && sampleSizeBox != null && sampleSizeBox.getSampleCount() > 0) {
            long[] numberOfSamplesInChunk = sampleToChunkBox.blowup(chunkOffsetBox.getChunkOffsets().length);
            if (sampleSizeBox.getSampleSize() > 0) {
                // Every sample has the same size!
                // no need to store each size separately
                // this happens when people use raw audio formats in MP4 (are you stupid guys???)
                // and assign each PCM sample its own MP4 sample
                offsets2Sizes = new DummyMap<Long, Long>(sampleSizeBox.getSampleSize());
                long sampleSize = sampleSizeBox.getSampleSize();
                for (int i = 0; i < numberOfSamplesInChunk.length; i++) {
                    long thisChunksNumberOfSamples = numberOfSamplesInChunk[i];
                    long sampleOffset = chunkOffsetBox.getChunkOffsets()[i];
                    for (int j = 0; j < thisChunksNumberOfSamples; j++) {
                        offsets2Sizes.put(sampleOffset, sampleSize);
                        sampleOffset += sampleSize;
                    }
                }
            } else {
                // the normal case where all samples have different sizes
                int sampleIndex = 0;
                long sampleSizes[] = sampleSizeBox.getSampleSizes();
                for (int i = 0; i < numberOfSamplesInChunk.length; i++) {
                    long thisChunksNumberOfSamples = numberOfSamplesInChunk[i];
                    long sampleOffset = chunkOffsetBox.getChunkOffsets()[i];
                    for (int j = 0; j < thisChunksNumberOfSamples; j++) {
                        long sampleSize = sampleSizes[sampleIndex];
                        offsets2Sizes.put(sampleOffset, sampleSize);
                        sampleOffset += sampleSize;
                        sampleIndex++;
                    }
                }

            }
        }

        // Next we add all samples from the fragments
        // in most cases - I've never seen it different it's either normal or fragmented.

        List<MovieExtendsBox> movieExtendsBoxes = trackBox.getParent().getBoxes(MovieExtendsBox.class);

        if (movieExtendsBoxes.size() > 0) {
            List<TrackExtendsBox> trackExtendsBoxes = movieExtendsBoxes.get(0).getBoxes(TrackExtendsBox.class);
            for (TrackExtendsBox trackExtendsBox : trackExtendsBoxes) {
                if (trackExtendsBox.getTrackId() == trackBox.getTrackHeaderBox().getTrackId()) {
                    for (MovieFragmentBox movieFragmentBox : trackBox.getIsoFile().getBoxes(MovieFragmentBox.class)) {
                        offsets2Sizes.putAll(getOffsets(movieFragmentBox, trackBox.getTrackHeaderBox().getTrackId(), trackExtendsBox));
                    }
                }
            }
        }


        // We have now a map from all sample offsets to their sizes
    }


    @Override
    public int size() {
        return offsets2Sizes.size();
    }

    // A simple
    private HashMap<MediaDataBox, Map<Long, SoftReference<ByteBuffer>>> cache = new HashMap<MediaDataBox, Map<Long, SoftReference<ByteBuffer>>>();
    private final long BUFFER_SIZE = 1024 * 1024;

    @Override
    public ByteBuffer get(int index) {
        // it is a two stage lookup: from index to offset to size
        Long offset = getOffsetKeys().get(index);
        int sampleSize = l2i(offsets2Sizes.get(offset));

        for (MediaDataBox mediaDataBox : mdats) {
            // todo MOVE ALL THAT STUFF INTO THE MDAT BOX. It shouldn't be here.
            long start = mdatStartCache.get(mediaDataBox);
            long end = mdatEndCache.get(mediaDataBox);
            if ((start <= offset) && (offset + sampleSize <= end)) {
                try {
                    ByteBuffer bb = mediaDataBox.getContent();
                    bb.position(l2i(offset - start));
                    ByteBuffer sample = bb.slice();
                    sample.limit(sampleSize);
                    return sample;
                } catch (MediaDataBox.MappingFailedRuntimeException e) {
                    // On 32 bit systems mapping big files may fail
                    // just read sample by sample then
                    // that's slow but at least it's working.
                    try {
                        Map<Long, SoftReference<ByteBuffer>> mdatsCache = cache.get(mediaDataBox);
                        if (mdatsCache != null) {
                            for (Map.Entry<Long, SoftReference<ByteBuffer>> entry : mdatsCache.entrySet()) {
                                if (entry.getKey() < offset) {
                                    if ((entry.getValue().get() != null) && ((entry.getKey() + entry.getValue().get().limit()) >= (offset + sampleSize))) {
                                        ByteBuffer cacheEntry = entry.getValue().get();
                                        cacheEntry.position((int) (offset - entry.getKey()));
                                        ByteBuffer cachedSample = cacheEntry.slice();
                                        cachedSample.limit(sampleSize);
                                        return cachedSample;
                                    }
                                }
                            }
                        } else {
                            mdatsCache = new HashMap<Long, SoftReference<ByteBuffer>>();
                            cache.put(mediaDataBox, mdatsCache);
                        }
                        ByteBuffer cacheEntry = mediaDataBox.getFileChannel().map(FileChannel.MapMode.READ_ONLY, offset, BUFFER_SIZE);
                        mdatsCache.put(offset, new SoftReference<ByteBuffer>(cacheEntry));
                        cacheEntry.position(0);
                        ByteBuffer cachedSample = cacheEntry.slice();
                        cachedSample.limit(sampleSize);
                        return cachedSample;
                    } catch (IOException e1) {
                        throw new RuntimeException(e1);
                    }
                }
            }
        }

        throw new RuntimeException("The sample with offset " + offset + " and size " + sampleSize + " is NOT located within an mdat");
    }

    Map<Long, Long> getOffsets(MovieFragmentBox moof, long trackId, TrackExtendsBox trackExtendsBox) {
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
                                sampleSize = trackExtendsBox.getDefaultSampleSize();
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