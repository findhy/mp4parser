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
import com.coremedia.iso.IsoBufferWrapper;
import com.coremedia.iso.IsoFile;

import javax.print.attribute.standard.MediaSize;
import java.io.IOException;


/**
 * A common base structure to contain general metadata. See ISO/IEC 14496-12 Ch. 8.44.1.
 */
public class MetaBox extends AbstractContainerBox {
    private int version = -1;
    private int flags = -1;

    public static final String TYPE = "meta";

    public MetaBox() {
        super(IsoFile.fourCCtoBytes(TYPE));
    }

    @Override
    public String getDisplayName() {
        return "Meta Box";
    }

    @Override
    public void parse(IsoBufferWrapper in, long size, BoxParser boxParser, Box lastMovieFragmentBox) throws IOException {
        long pos = in.position();
        in.skip(4);
        if ("hdlr".equals(IsoFile.bytesToFourCC(in.read(4)))) {
            //  this is apple bullshit - it's NO FULLBOX
            in.position(pos);
        } else {
            in.position(pos);
            version = in.readUInt8();
            flags = in.readUInt24();
        }
        super.parse(in, size, boxParser, lastMovieFragmentBox);
    }

    public boolean isMp4Box() {
        return version != -1 && flags != -1;
    }

    public boolean isAppleBox() {
        return !(version != -1 && flags != -1);
    }
}
