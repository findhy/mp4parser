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

package com.coremedia.iso.boxes;

import com.coremedia.iso.BoxParser;
import com.coremedia.iso.ChannelHelper;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.IsoTypeWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static com.coremedia.iso.boxes.CastUtils.l2i;

/**
 * A basic ISO box. No full box.
 */
public abstract class AbstractBox implements Box {
    protected ByteBuffer content;

    public long getSize() {
        return (content == null ? getContentSize() : content.capacity()) + getHeaderSize() + (deadBytes == null ? 0 : deadBytes.capacity());
    }

    protected long getHeaderSize() {
        return 4 + // size
                4 + // type
                ((content != null ? content.capacity() :
                        getContentSize()) >= ((1L << 32) - 8) ? 8 : 0) + // 32bit - 8 byte size and type
                (UserBox.TYPE.equals(getType()) ? 16 : 0);
    }

    /**
     * Gets the box's content size. This excludes all header fields:
     * <ul>
     * <li>4 byte size</li>
     * <li>4 byte type</li>
     * <li>(large length - 8 bytes)</li>
     * <li>(user type - 16 bytes)</li>
     * </ul>
     * <p/>
     * Flags and version of a full box need to be taken into account.
     *
     * @return Gets the box's content size in bytes
     */
    protected abstract long getContentSize();

    protected String type;
    private byte[] userType;
    private ContainerBox parent;


    protected AbstractBox(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }


    public byte[] getUserType() {
        return userType;
    }

    public void setUserType(byte[] userType) {
        this.userType = userType;
    }

    public ContainerBox getParent() {
        return parent;
    }

    public void setParent(ContainerBox parent) {
        this.parent = parent;
    }


    public IsoFile getIsoFile() {
        return parent.getIsoFile();
    }

    /**
     * Pareses the given IsoBufferWrapper and returns the remaining bytes.
     *
     * @param in          the (part of the) iso file to parse
     * @param contentSize expected contentSize of the box
     * @param boxParser   creates inner boxes
     * @throws IOException in case of an I/O error.
     */
    public void parse(ReadableByteChannel in, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {
        if (in instanceof FileChannel && contentSize > 1024 * 1024) {
            // It's quite expensive to map a file into the memory. Just do it when the box is larger than a MB.
            content = ((FileChannel) in).map(FileChannel.MapMode.READ_ONLY, ((FileChannel) in).position(), contentSize);
        } else {
            assert contentSize < Integer.MAX_VALUE;
            content = ChannelHelper.readFully(in, contentSize);
        }
    }

    /**
     * Parses the boxes fields.
     */
    public final void parseDetails() {
        if (content != null) {
            _parseDetails();
            if (content.remaining() > 0) {
                deadBytes = content.slice();
            }
            content = null;
        }
    }

    /**
     * Implement the actual parsing of the box's fields here. External classes will always call
     * {@link #parseDetails()} which encapsulates the call to this method with some safeguards.
     */
    public abstract void _parseDetails();

    protected ByteBuffer deadBytes = null;

    public ByteBuffer getDeadBytes() {
        return deadBytes;
    }

    public void setDeadBytes(ByteBuffer newDeadBytes) {
        deadBytes = newDeadBytes;
    }

    public void getHeader(ByteBuffer byteBuffer) {
        if (isSmallBox()) {
            IsoTypeWriter.writeUInt32(byteBuffer, this.getSize());
            byteBuffer.put(IsoFile.fourCCtoBytes(getType()));
        } else {
            IsoTypeWriter.writeUInt32(byteBuffer, 1);
            byteBuffer.put(IsoFile.fourCCtoBytes(getType()));
            IsoTypeWriter.writeUInt64(byteBuffer, getSize());
        }
        if (UserBox.TYPE.equals(getType())) {
            byteBuffer.put(userType);
        }


    }

    private boolean isSmallBox() {
        return (content == null ? (getContentSize() + (deadBytes != null ? deadBytes.capacity() : 0) + 8) : content.capacity()) < 1L<<32;
    }


    public void getBox(WritableByteChannel os) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(l2i(getSize()));
        getHeader(bb);
        if (content == null) {
            getContent(bb);
            if (deadBytes != null) {
                deadBytes.rewind();
                while (deadBytes.remaining() > 0) {
                    bb.put(deadBytes);
                }
            }
        } else {
            content.rewind();
            bb.put(content);
        }
    }

    /**
     * Writes the box's content into the given <code>ByteBuffer</code>. This must include flags
     * and version in case of a full box. <code>bb</code> has been initialized with
     * <code>getContentSize()</code> bytes.
     *
     * @param bb the box's content-sink.
     * @throws IOException in case of an exception in the underlying <code>OutputStream</code>.
     */
    protected abstract void getContent(ByteBuffer bb) throws IOException;


}
