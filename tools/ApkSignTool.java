import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ApkSignTool {
    private ApkSignTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        if ("sign".equals(args[0])) {
            if (args.length != 7) usage();
            sign(new File(args[1]), args[2], args[3].toCharArray(), args[4].toCharArray(),
                    new File(args[5]), new File(args[6]));
        } else if ("verify".equals(args[0])) {
            if (args.length != 2) usage();
            verify(new File(args[1]));
        } else {
            usage();
        }
    }

    private static void sign(File keyStoreFile, String alias, char[] storePass, char[] keyPass,
                             File input, File output) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        FileInputStream stream = new FileInputStream(keyStoreFile);
        try {
            keyStore.load(stream, storePass);
        } finally {
            stream.close();
        }
        Key key = keyStore.getKey(alias, keyPass);
        if (!(key instanceof PrivateKey)) {
            throw new IllegalArgumentException("Alias does not contain a private key: " + alias);
        }
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            throw new IllegalArgumentException("Alias has no certificate chain: " + alias);
        }
        List<X509Certificate> certificates = new ArrayList<X509Certificate>();
        for (Certificate certificate : chain) certificates.add((X509Certificate) certificate);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                alias, (PrivateKey) key, certificates).build();
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("Could not replace " + output);
        }
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setCreatedBy("Codex Meter reproducible local build")
                .build()
                .sign();
        System.out.println("Signed " + output);
    }

    private static void verify(File apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(26)
                .setMaxCheckedPlatformVersion(36)
                .build()
                .verify();
        System.out.println("Verified: " + result.isVerified());
        System.out.println("Verified using v1: " + result.isVerifiedUsingV1Scheme());
        System.out.println("Verified using v2: " + result.isVerifiedUsingV2Scheme());
        System.out.println("Signer certificates: " + result.getSignerCertificates().size());
        for (X509Certificate certificate : result.getSignerCertificates()) {
            System.out.println("Signer subject: " + certificate.getSubjectX500Principal());
            System.out.println("Signer serial: " + certificate.getSerialNumber().toString(16));
        }
        for (ApkVerifier.IssueWithParams warning : result.getWarnings()) {
            System.out.println("WARNING: " + warning);
        }
        for (ApkVerifier.IssueWithParams error : result.getErrors()) {
            System.out.println("ERROR: " + error);
        }
        if (!result.isVerified()) System.exit(2);
    }

    private static void usage() {
        System.err.println("Usage:\n"
                + "  ApkSignTool sign <keystore> <alias> <storepass> <keypass> <input.apk> <output.apk>\n"
                + "  ApkSignTool verify <apk>");
        System.exit(64);
    }
}
