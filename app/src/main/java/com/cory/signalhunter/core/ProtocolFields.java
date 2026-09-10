package com.cory.signalhunter.core;

/** Bounded decoders; offsets refer to the preserved element payload. */
public final class ProtocolFields {
    private ProtocolFields() { }
    public static byte[] bytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("Odd hex");
        byte[] out = new byte[hex.length()/2];
        for (int i=0; i<out.length; i++) {
            int a = Character.digit(hex.charAt(i*2),16);
            int b = Character.digit(hex.charAt(i*2+1),16);
            if (a<0 || b<0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte)((a<<4)|b);
        }
        return out;
    }
    private static int u(byte[] b, int i) { return b[i]&255; }
    private static int le(byte[] b, int i) {
        return u(b,i) | (u(b,i+1)<<8);
    }
    private static String hex(byte[] b, int start, int length) {
        StringBuilder s = new StringBuilder();
        for (int i=start; i<start+length; i++) {
            s.append("0123456789abcdef".charAt(u(b,i)>>4));
            s.append("0123456789abcdef".charAt(u(b,i)&15));
        }
        return s.toString();
    }
    public static String wifi(int id, int ext, String value) {
        try {
            byte[] b = bytes(value);
            String prefix = "PARSED IE " + id + "/" + ext + ": ";
            if (id==5 && b.length>=2) return prefix
                + "DTIM count=" + u(b,0) + ", period=" + u(b,1);
            if (id==11 && b.length>=5) return prefix
                + "QBSS stations=" + le(b,0)
                + ", advertised utilization=" + u(b,2) + "/255"
                + ", admission capacity=" + le(b,3) + " ×32us";
            if (id==221 && b.length>=3) return prefix
                + "Vendor OUI=" + hex(b,0,3)
                + " (no vendor database / no identity inference)";
            if (id==48) {
                if (b.length<8) return prefix + "TRUNCATED RSN";
                StringBuilder s = new StringBuilder(prefix);
                s.append("RSN version=").append(le(b,0));
                s.append(" group selector=").append(hex(b,2,4));
                int count = le(b,6), p=8;
                if (count>(b.length-p)/4) return prefix+"TRUNCATED pairwise";
                s.append(" pairwise=");
                for (int i=0;i<count;i++,p+=4)
                    s.append(hex(b,p,4)).append(' ');
                if (p+2>b.length) return prefix+"TRUNCATED AKM count";
                count=le(b,p); p+=2;
                if (count>(b.length-p)/4) return prefix+"TRUNCATED AKM";
                s.append(" AKM=");
                for (int i=0;i<count;i++,p+=4) {
                    String suite=hex(b,p,4); s.append(suite);
                    if (suite.equals("000fac08")) s.append("(SAE)");
                    if (suite.equals("000fac12")) s.append("(OWE)");
                    s.append(' ');
                }
                if (p+2<=b.length) {
                    int caps=le(b,p);
                    s.append(" PMF capable=").append((caps&128)!=0);
                    s.append(" required=").append((caps&64)!=0);
                } else s.append(" RSN capabilities absent");
                return s.toString();
            }
            return prefix + "raw payload retained (" + b.length + " bytes)";
        } catch (RuntimeException e) { return "MALFORMED IE " + id; }
    }
    public static String bluetooth(String value) {
        try {
            byte[] b=bytes(value); StringBuilder s=new StringBuilder();
            for (int p=0; p<b.length;) {
                int n=u(b,p++); if(n==0) break;
                if(n>b.length-p) { s.append("TRUNCATED AD\n"); break; }
                int type=u(b,p);
                s.append("AD type ").append(type).append(" payload=")
                    .append(hex(b,p+1,n-1));
                if(type==1 && n>=2) s.append(" flags=").append(u(b,p+1));
                if(type==10 && n>=2) s.append(" Tx power=")
                    .append(b[p+1]).append(" dBm (advertised)");
                if(type==255 && n>=3) s.append(" manufacturer ID=")
                    .append(le(b,p+1));
                if(type==2 || type==3) s.append(" UUID16 list");
                if(type==6 || type==7) s.append(" UUID128 list");
                s.append('\n'); p+=n;
            }
            return s.toString();
        } catch(RuntimeException e) { return "MALFORMED AD"; }
    }
}
