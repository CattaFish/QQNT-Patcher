package com.tencent.qqnt.patch.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class MiniProto {

    public static class Reader {
        private final byte[] data;
        private int pos;
        private final int limit;

        public Reader(byte[] data) {
            this.data = data;
            this.pos = 0;
            this.limit = data != null ? data.length : 0;
        }

        public boolean hasMore() {
            return data != null && pos < limit;
        }

        public int getPos() {
            return pos;
        }

        public void skip(int n) {
            if (n > 0) {
                pos = Math.min(limit, pos + n);
            }
        }

        public long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            return result;
        }

        public byte[] readBytes() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > limit) return null;
            byte[] dest = new byte[len];
            System.arraycopy(data, pos, dest, 0, len);
            pos += len;
            return dest;
        }
    }

    public static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    public static byte[] getBytes(byte[] data, int targetField) {
        if (data == null) return null;
        Reader reader = new Reader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                reader.readVarint();
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                byte[] sub = reader.readBytes();
                if (sub == null) break;
                if (field == targetField) return sub;
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return null;
    }

    public static String getString(byte[] data, int targetField) {
        byte[] b = getBytes(data, targetField);
        return b != null ? new String(b, StandardCharsets.UTF_8) : "";
    }

    public static long getVarint(byte[] data, int targetField) {
        if (data == null) return 0L;
        Reader reader = new Reader(data);
        while (reader.hasMore()) {
            long tag = reader.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long val = reader.readVarint();
                if (field == targetField) return val;
            } else if (wire == 1) {
                reader.skip(8);
            } else if (wire == 2) {
                int len = (int) reader.readVarint();
                if (len < 0) break;
                reader.skip(len);
            } else if (wire == 5) {
                reader.skip(4);
            } else {
                break;
            }
        }
        return 0L;
    }

    public static byte[] subArray(byte[] src, int start) {
        if (src == null || start < 0 || start >= src.length) return new byte[0];
        int length = src.length - start;
        byte[] dest = new byte[length];
        System.arraycopy(src, start, dest, 0, length);
        return dest;
    }

    public static byte[] subArray(byte[] src, int start, int length) {
        if (src == null || start < 0 || length <= 0 || start + length > src.length) return new byte[0];
        byte[] dest = new byte[length];
        System.arraycopy(src, start, dest, 0, length);
        return dest;
    }
}