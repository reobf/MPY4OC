package reobf.mpy4oc.main.arch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The BIOS texts earlier releases put on EEPROMs. An EEPROM keeps the code it was made
 * with, so a machine holding an unchanged copy of one of these boots the bundled BIOS
 * instead: it is the same program, fixed. (Their /init.py reader decoded each 2048-byte
 * read on its own, so a multi-byte character across a read boundary stopped the boot
 * once bytes.decode became strict.) A copy anyone edited differs and is left alone.
 * When bios.py or mpyos/bios.py changes, add the SHA-256 of the released text here;
 * BiosVersionsTest checks every version in git history is listed.
 */
public final class BiosVersions {
    private BiosVersions() {}

    /** SHA-256 of each released text, with carriage returns removed. */
    static final Set<String> EARLIER = new HashSet<>(Arrays.asList(
            "64ad478b7049a74136f0c95cd1acd744a10b54365338001d1ab3af1f13d3c460",   // bios/bios.py c526d89
            "c0c189dee8cf92f2017590c1b3cc4f8fd493639aabb24ba1c2e76330cd2f926d",   // the same, clipped to 4096 bytes
            "7faf4563d1c01fe7d981b0cdeddbdea0a9cb8ef8c2d23600410a0335396d81cd",   // bios/bios.py 27dcd7a
            "0e0b3d6e4b7e85f35a38fd737ad98e3ac6216a4560f386f882320ff4328da01e",   // bios/bios.py 01f20db
            "e8f8a61c57138ed5794b5342c44679638d1c39239c9df3ae5ffa26576bbb838d"));  // mpyos/bios.py c526d89

    /** Whether {@code source} is an unchanged BIOS of an earlier release. */
    public static boolean earlierRelease(String source) {
        return source != null && EARLIER.contains(sha256(source.replace("\r", "")));
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xFF));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);   // every JRE has SHA-256
        }
    }
}
