/*
 * Copyright © 2012, castLabs GmbH, www.castlabs.com
 */

package com.googlecode.mp4parser.boxes.cenc;


import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.SampleImpl;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractList;
import java.util.List;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

public class CommonEncryptionSampleList extends AbstractList<Sample> {

    List<CencSampleAuxiliaryDataFormat> auxiliaryDataFormats;
    SecretKey secretKey;
    List<Sample> parent;

    public CommonEncryptionSampleList(
            SecretKey secretKey,
            List<Sample> parent,
            List<CencSampleAuxiliaryDataFormat> auxiliaryDataFormats) {
        this.auxiliaryDataFormats = auxiliaryDataFormats;
        this.secretKey = secretKey;
        this.parent = parent;

    }


    Cipher getCipher(SecretKey sk, byte[] iv) {
        byte[] fullIv = new byte[16];
        System.arraycopy(iv, 0, fullIv, 0, iv.length);
        // The IV
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sk, new IvParameterSpec(fullIv));
            return cipher;
        } catch (InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (NoSuchPaddingException e) {
            throw new RuntimeException(e);
        }


    }

    @Override
    public Sample get(int index) {
        Sample sampleObj = parent.get(index);
        ByteBuffer sample = (ByteBuffer) sampleObj.asByteBuffer().rewind();
        ByteBuffer encSample = ByteBuffer.allocate(sample.limit());

        CencSampleAuxiliaryDataFormat entry =  auxiliaryDataFormats.get(index);
        Cipher cipher = getCipher(secretKey, entry.iv);
        try {
            if (entry.pairs != null) {
                for (CencSampleAuxiliaryDataFormat.Pair pair : entry.pairs) {
                    byte[] clears = new byte[pair.clear];
                    sample.get(clears);
                    encSample.put(clears);
                    if (pair.encrypted > 0) {
                        byte[] toBeEncrypted = new byte[l2i(pair.encrypted)];
                        sample.get(toBeEncrypted);
                        assert (toBeEncrypted.length % 16) == 0;
                        byte[] encrypted = cipher.update(toBeEncrypted);
                        assert encrypted.length == toBeEncrypted.length;
                        encSample.put(encrypted);
                    }

                }
            } else {
                byte[] fullyEncryptedSample = new byte[sample.limit()];
                sample.get(fullyEncryptedSample);
                encSample.put(cipher.doFinal(fullyEncryptedSample));
            }
            sample.rewind();
        } catch (IllegalBlockSizeException e) {
            throw new RuntimeException(e);
        } catch (BadPaddingException e) {
            throw new RuntimeException(e);
        }
        encSample.rewind();
        return new SampleImpl(encSample);
    }


    @Override
    public int size() {
        return parent.size();
    }

}
