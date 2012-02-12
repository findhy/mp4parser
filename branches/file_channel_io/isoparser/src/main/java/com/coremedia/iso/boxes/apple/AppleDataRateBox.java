/*
 * Copyright 2009 castLabs GmbH, Berlin
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

package com.coremedia.iso.boxes.apple;

import com.coremedia.iso.*;
import com.coremedia.iso.boxes.AbstractFullBox;
import com.coremedia.iso.boxes.Box;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AppleDataRateBox extends AbstractFullBox {
    public static final String TYPE = "rmdr";
    private long dataRate;

    public AppleDataRateBox() {
        super(IsoFile.fourCCtoBytes(TYPE));
    }

    protected long getContentSize() {
        return 8;
    }

    @Override
    public void _parseDetails() {
        parseVersionAndFlags();
        dataRate = IsoTypeReader.readUInt32(content);
    }

    @Override
    protected void getContent(ByteBuffer bb) throws IOException {
        writeVersionAndFlags(bb);
        IsoTypeWriter.writeUInt32(bb, dataRate);
    }


    public long getDataRate() {
        return dataRate;
    }
}
