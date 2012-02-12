/*  
 * Copyright 2008 CoreMedia AG, Hamburg
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

package com.coremedia.iso.boxes.sampleentry;

import com.coremedia.iso.*;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.ContainerBox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static com.coremedia.iso.boxes.CastUtils.l2i;

/**
 * Contains basic information about the audio samples in this track. Format-specific information
 * is appened as boxes after the data described in ISO/IEC 14496-12 chapter 8.16.2.
 */
public class AudioSampleEntry extends SampleEntry implements ContainerBox {

    public static final String TYPE1 = "samr";
    public static final String TYPE2 = "sawb";
    public static final String TYPE3 = "mp4a";
    public static final String TYPE4 = "drms";
    public static final String TYPE5 = "alac";
    public static final String TYPE7 = "owma";
    public static final String TYPE8 = "ac-3"; /* ETSI TS 102 366 1.2.1 Annex F */

    /**
     * Identifier for an encrypted audio track.
     *
     * @see com.coremedia.iso.boxes.ProtectionSchemeInformationBox
     */
    public static final String TYPE_ENCRYPTED = "enca";

    private int channelCount;
    private int sampleSize;
    private long sampleRate;
    private int soundVersion;
    private int compressionId;
    private int packetSize;
    private long samplesPerPacket;
    private long bytesPerPacket;
    private long bytesPerFrame;
    private long bytesPerSample;

    private int reserved1;
    private long reserved2;
    private byte[] soundVersion2Data;
    private BoxParser boxParser;

    public AudioSampleEntry(String type) {
        super(type);
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public long getSampleRate() {
        return sampleRate;
    }

    public int getSoundVersion() {
        return soundVersion;
    }

    public int getCompressionId() {
        return compressionId;
    }

    public int getPacketSize() {
        return packetSize;
    }

    public long getSamplesPerPacket() {
        return samplesPerPacket;
    }

    public long getBytesPerPacket() {
        return bytesPerPacket;
    }

    public long getBytesPerFrame() {
        return bytesPerFrame;
    }

    public long getBytesPerSample() {
        return bytesPerSample;
    }


    @Override
    public void _parseDetails() {
        _parseReservedAndDataReferenceIndex();    //parses the six reserved bytes and dataReferenceIndex
        // 8 bytes already parsed
        //reserved bits - used by qt
        soundVersion = IsoTypeReader.readUInt16(content);

        //reserved
        reserved1 = IsoTypeReader.readUInt16(content);
        reserved2 = IsoTypeReader.readUInt32(content);

        channelCount = IsoTypeReader.readUInt16(content);
        sampleSize = IsoTypeReader.readUInt16(content);
        //reserved bits - used by qt
        compressionId = IsoTypeReader.readUInt16(content);
        //reserved bits - used by qt
        packetSize = IsoTypeReader.readUInt16(content);
        //sampleRate = in.readFixedPoint1616();
        sampleRate = IsoTypeReader.readUInt32(content) >>> 16;

        //more qt stuff - see http://mp4v2.googlecode.com/svn-history/r388/trunk/src/atom_sound.cpp
        if (soundVersion > 0) {
            samplesPerPacket = IsoTypeReader.readUInt32(content);
            bytesPerPacket = IsoTypeReader.readUInt32(content);
            bytesPerFrame = IsoTypeReader.readUInt32(content);
            bytesPerSample = IsoTypeReader.readUInt32(content);
        }
        if (soundVersion == 2) {
            
            soundVersion2Data = new byte[20];
            content.get(20);
        }
        _parseChildBoxes();

    }



    @Override
    protected long getContentSize() {
        long contentSize = 28;
        contentSize += soundVersion > 0 ? 16 : 0;
        contentSize += soundVersion == 2 ? 20 : 0;
        for (Box boxe : boxes) {
            contentSize += boxe.getSize();
        }
        return contentSize;
    }


    public String toString() {
        return "AudioSampleEntry";
    }

    @Override
    protected void getContent(ByteBuffer bb) throws IOException {
        _writeReservedAndDataReferenceIndex(bb);
        IsoTypeWriter.writeUInt16(bb,soundVersion);
        IsoTypeWriter.writeUInt16(bb,reserved1);
        IsoTypeWriter.writeUInt32(bb,reserved2);
        IsoTypeWriter.writeUInt16(bb,getChannelCount());
        IsoTypeWriter.writeUInt16(bb,getSampleSize());
        IsoTypeWriter.writeUInt16(bb,0);
        IsoTypeWriter.writeUInt16(bb,0);
        //isos.writeFixedPont1616(getSampleRate());
        IsoTypeWriter.writeUInt32(bb,getSampleRate() << 16);
        if (soundVersion > 0) {
            IsoTypeWriter.writeUInt32(bb,samplesPerPacket);
            IsoTypeWriter.writeUInt32(bb,bytesPerPacket);
            IsoTypeWriter.writeUInt32(bb,bytesPerFrame);
            IsoTypeWriter.writeUInt32(bb,bytesPerSample);
        }

        if (soundVersion == 2) {
            bb.put(soundVersion2Data);
        }
        _writeChildBoxes(bb);
    }
}
