/*
 * Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.font;

import java.io.*;

/**
 * Stores glyph-related data, used in the pure-java glyphcache.
 *
 * @author Clemens Eisserer
 */

public final class XRGlyphCacheEntry {
    long glyphInfoPtr, bgraGlyphInfoPtr;

    int lastUsed;
    boolean pinned;

    int xOff;
    int yOff;

    int glyphSet;

    public XRGlyphCacheEntry(long glyphInfoPtr, GlyphList gl) {
        this.glyphInfoPtr = glyphInfoPtr;

        /* TODO: Does it make sense to cache results? */
        xOff = Math.round(getXAdvance());
        yOff = Math.round(getYAdvance());
    }

    public long getBgraGlyphInfoPtr() {
        return bgraGlyphInfoPtr;
    }

    public void setBgraGlyphInfoPtr(long bgraGlyphInfoPtr) {
        this.bgraGlyphInfoPtr = bgraGlyphInfoPtr;
    }

    public int getXOff() {
        return xOff;
    }

    public int getYOff() {
        return yOff;
    }

    public void setGlyphSet(int glyphSet) {
        this.glyphSet = glyphSet;
    }

    public int getGlyphSet() {
        return glyphSet;
    }

    public static int getGlyphID(long glyphInfoPtr) {
        // We need to access the GlyphID as a long because the
        // corresponding field in the underlying C data-structure is of type
        // 'void*' (see field 'cellInfo' of struct 'GlyphInfo'
        // in src/share/native/sun/font/fontscalerdefs.h).
        // On 64-bit Big-endian architectures it would be wrong to access this
        // field as an int.
        return (int)StrikeCache.getGlyphCellInfo(glyphInfoPtr);
    }

    public static void setGlyphID(long glyphInfoPtr, int id) {
        // We need to access the GlyphID as a long because the
        // corresponding field in the underlying C data-structure is of type
        // 'void*' (see field 'cellInfo' of struct 'GlyphInfo'
        // in src/share/native/sun/font/fontscalerdefs.h).
        // On 64-bit Big-endian architectures it would be wrong to access this
        // field as an int
        // See Java_sun_java2d_xr_XRBackendNative_XRAddGlyphsNative()
        // in src/unix/native/sun/java2d/x11/XRBackendNative.c
        StrikeCache.setGlyphCellInfo(glyphInfoPtr, (long)id);
    }

    public int getGlyphID() {
        return getGlyphID(glyphInfoPtr);
    }

    public void setGlyphID(int id) {
        setGlyphID(glyphInfoPtr, id);
    }

    public float getXAdvance() {
        return StrikeCache.getGlyphXAdvance(glyphInfoPtr);
    }

    public float getYAdvance() {
        return StrikeCache.getGlyphYAdvance(glyphInfoPtr);
    }

    public int getSourceRowBytes() {
        return StrikeCache.getGlyphRowBytes(glyphInfoPtr);
    }

    public int getWidth() {
        return StrikeCache.getGlyphWidth(glyphInfoPtr);
    }

    public int getHeight() {
        return StrikeCache.getGlyphHeight(glyphInfoPtr);
    }

    public void writePixelData(ByteArrayOutputStream os, boolean uploadAsLCD) {
        long pixelDataAddress = StrikeCache.getGlyphImagePtr(glyphInfoPtr);
        if (pixelDataAddress == 0L) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        int rowBytes = getSourceRowBytes();
        int paddedWidth = getPaddedWidth();

        byte[] pixelBytes = StrikeCache.getGlyphPixelBytes(glyphInfoPtr);
        if (getType() == Type.GRAYSCALE) {
            int subglyphs = getSubpixelResolutionX() * getSubpixelResolutionY();
            for (int subglyph = 0; subglyph < subglyphs; subglyph++) {
                for (int line = 0; line < height; line++) {
                    for(int x = 0; x < paddedWidth; x++) {
                        if(x < width) {
                            os.write(pixelBytes[(line * rowBytes + x)]);
                        }else {
                            /*pad to multiple of 4 bytes per line*/
                            os.write(0);
                        }
                    }
                }
                pixelDataAddress += height * rowBytes;
            }
        } else {
            for (int line = 0; line < height; line++) {
                int rowStart = line * rowBytes;
                int rowBytesWidth = width * 3;
                int srcpix = 0;
                while (srcpix < rowBytesWidth) {
                    os.write(pixelBytes[rowStart + srcpix + 2]);
                    os.write(pixelBytes[rowStart + srcpix + 1]);
                    os.write(pixelBytes[rowStart + srcpix + 0]);
                    os.write(255);
                    srcpix += 3;
                }
            }
        }
    }

    public float getTopLeftXOffset() {
        return StrikeCache.getGlyphTopLeftX(glyphInfoPtr);
    }

    public float getTopLeftYOffset() {
        return StrikeCache.getGlyphTopLeftY(glyphInfoPtr);
    }

    public byte getSubpixelResolutionX() {
        byte rx = StrikeCache.getGlyphSubpixelResolutionX(glyphInfoPtr);
        return rx < 1 ? 1 : rx;
    }

    public byte getSubpixelResolutionY() {
        byte ry = StrikeCache.getGlyphSubpixelResolutionY(glyphInfoPtr);
        return ry < 1 ? 1 : ry;
    }

    public long getGlyphInfoPtr() {
        return glyphInfoPtr;
    }

    public Type getType() {
        byte format = StrikeCache.getGlyphFormat(glyphInfoPtr);
        if (format == StrikeCache.PIXEL_FORMAT_GREYSCALE) {
            return Type.GRAYSCALE;
        } else if (format == StrikeCache.PIXEL_FORMAT_LCD) {
            return Type.LCD;
        } else if (format == StrikeCache.PIXEL_FORMAT_BGRA) {
            return Type.BGRA;
        } else {
            throw new IllegalStateException("Unknown glyph format: " + format);
        }
    }

    public int getPaddedWidth() {
        return getType() == Type.GRAYSCALE ?
                (int) Math.ceil(getWidth() / 4.0) * 4 : getWidth();
    }

    public int getDestinationRowBytes() {
        return getType() == Type.GRAYSCALE ? getPaddedWidth() : getWidth() * 4;
    }

    public int getGlyphDataLenth() {
        return getDestinationRowBytes() * getHeight();
    }

    public void setPinned() {
        pinned = true;
    }

    public void setUnpinned() {
        pinned = false;
    }

    public int getLastUsed() {
        return lastUsed;
    }

    public void setLastUsed(int lastUsed) {
        this.lastUsed = lastUsed;
    }

    public int getPixelCnt() {
        return getWidth() * getHeight() * getSubpixelResolutionX() * getSubpixelResolutionY();
    }

    public boolean isPinned() {
        return pinned;
    }


    public enum Type {
        GRAYSCALE,
        LCD,
        BGRA
    }


}
