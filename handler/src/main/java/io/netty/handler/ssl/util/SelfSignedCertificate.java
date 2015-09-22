/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.ssl.util;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Generates a temporary self-signed certificate for testing purposes.
 * <p>
 * <strong>NOTE:</strong>
 * Never use the certificate and private key generated by this class in production.
 * It is purely for testing purposes, and thus it is very insecure.
 * It even uses an insecure pseudo-random generator for faster generation internally.
 * </p><p>
 * A X.509 certificate file and a RSA private key file are generated in a system's temporary directory using
 * {@link java.io.File#createTempFile(String, String)}, and they are deleted when the JVM exits using
 * {@link java.io.File#deleteOnExit()}.
 * </p><p>
 * At first, this method tries to use OpenJDK's X.509 implementation (the {@code sun.security.x509} package).
 * If it fails, it tries to use <a href="http://www.bouncycastle.org/">Bouncy Castle</a> as a fallback.
 * </p>
 */
public final class SelfSignedCertificate {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SelfSignedCertificate.class);

    /** Current time minus 1 year, just in case software clock goes back due to time synchronization */
    private static final Date DEFAULT_NOT_BEFORE = new Date(SystemPropertyUtil.getLong(
            "io.netty.selfSignedCertificate.defaultNotBefore", System.currentTimeMillis() - 86400000L * 365));
    /** The maximum possible value in X.509 specification: 9999-12-31 23:59:59 */
    private static final Date DEFAULT_NOT_AFTER = new Date(SystemPropertyUtil.getLong(
            "io.netty.selfSignedCertificate.defaultNotAfter", 253402300799000L));

    private final File certificate;
    private final File privateKey;
    private final X509Certificate cert;
    private final PrivateKey key;

    /**
     * Creates a new instance.
     */
    public SelfSignedCertificate() throws CertificateException {
        this(DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
    }

    /**
     * Creates a new instance.
     * @param notBefore Certificate is not valid before this time
     * @param notAfter Certificate is not valid after this time
     */
    public SelfSignedCertificate(Date notBefore, Date notAfter) throws CertificateException {
        this("example.com");
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    public SelfSignedCertificate(String fqdn) throws CertificateException {
        this(fqdn, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param notBefore Certificate is not valid before this time
     * @param notAfter Certificate is not valid after this time
     */
    public SelfSignedCertificate(String fqdn, Date notBefore, Date notAfter) throws CertificateException {
        // Bypass entrophy collection by using insecure random generator.
        // We just want to generate it without any delay because it's for testing purposes only.
        this(fqdn, ThreadLocalInsecureRandom.current(), 1024, notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link java.security.SecureRandom} to use
     * @param bits the number of bits of the generated private key
     */
    public SelfSignedCertificate(String fqdn, SecureRandom random, int bits) throws CertificateException {
        this(fqdn, random, bits, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link java.security.SecureRandom} to use
     * @param bits the number of bits of the generated private key
     * @param notBefore Certificate is not valid before this time
     * @param notAfter Certificate is not valid after this time
     */
    public SelfSignedCertificate(String fqdn, SecureRandom random, int bits, Date notBefore, Date notAfter)
            throws CertificateException {
        // Generate an RSA key pair.
        final KeyPair keypair;
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(bits, random);
            keypair = keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            // Should not reach here because every Java implementation must have RSA key pair generator.
            throw new Error(e);
        }

        String[] paths;
        try {
            // Try the OpenJDK's proprietary implementation.
            paths = OpenJdkSelfSignedCertGenerator.generate(fqdn, keypair, random, notBefore, notAfter);
        } catch (Throwable t) {
            logger.debug("Failed to generate a self-signed X.509 certificate using sun.security.x509:", t);
            try {
                // Try Bouncy Castle if the current JVM didn't have sun.security.x509.
                paths = BouncyCastleSelfSignedCertGenerator.generate(fqdn, keypair, random, notBefore, notAfter);
            } catch (Throwable t2) {
                logger.debug("Failed to generate a self-signed X.509 certificate using Bouncy Castle:", t2);
                throw new CertificateException(
                        "No provider succeeded to generate a self-signed certificate. " +
                                "See debug log for the root cause.");
            }
        }

        certificate = new File(paths[0]);
        privateKey = new File(paths[1]);
        key = keypair.getPrivate();
        try {
            cert = (X509Certificate) CertificateFactory.getInstance("X509").generateCertificate(
                    new FileInputStream(certificate));
        } catch (Exception e) {
            throw new CertificateEncodingException(e);
        }
    }

    /**
     * Returns the generated X.509 certificate file in PEM format.
     */
    public File certificate() {
        return certificate;
    }

    /**
     * Returns the generated RSA private key file in PEM format.
     */
    public File privateKey() {
        return privateKey;
    }

    /**
     *  Returns the generated X.509 certificate.
     */
    public X509Certificate cert() {
        return cert;
    }

    /**
     * Returns the generated RSA private key.
     */
    public PrivateKey key() {
        return key;
    }

    /**
     * Deletes the generated X.509 certificate file and RSA private key file.
     */
    public void delete() {
        safeDelete(certificate);
        safeDelete(privateKey);
    }

    static String[] newSelfSignedCertificate(
            String fqdn, PrivateKey key, X509Certificate cert) throws IOException, CertificateEncodingException {

        // Encode the private key into a file.
        String keyText = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.encode(Unpooled.wrappedBuffer(key.getEncoded()), true).toString(CharsetUtil.US_ASCII) +
                "\n-----END PRIVATE KEY-----\n";

        File keyFile = File.createTempFile("keyutil_" + fqdn + '_', ".key");
        keyFile.deleteOnExit();

        OutputStream keyOut = new FileOutputStream(keyFile);
        try {
            keyOut.write(keyText.getBytes(CharsetUtil.US_ASCII));
            keyOut.close();
            keyOut = null;
        } finally {
            if (keyOut != null) {
                safeClose(keyFile, keyOut);
                safeDelete(keyFile);
            }
        }

        // Encode the certificate into a CRT file.
        String certText = "-----BEGIN CERTIFICATE-----\n" +
                Base64.encode(Unpooled.wrappedBuffer(cert.getEncoded()), true).toString(CharsetUtil.US_ASCII) +
                "\n-----END CERTIFICATE-----\n";

        File certFile = File.createTempFile("keyutil_" + fqdn + '_', ".crt");
        certFile.deleteOnExit();

        OutputStream certOut = new FileOutputStream(certFile);
        try {
            certOut.write(certText.getBytes(CharsetUtil.US_ASCII));
            certOut.close();
            certOut = null;
        } finally {
            if (certOut != null) {
                safeClose(certFile, certOut);
                safeDelete(certFile);
                safeDelete(keyFile);
            }
        }

        return new String[] { certFile.getPath(), keyFile.getPath() };
    }

    private static void safeDelete(File certFile) {
        if (!certFile.delete()) {
            logger.warn("Failed to delete a file: " + certFile);
        }
    }

    private static void safeClose(File keyFile, OutputStream keyOut) {
        try {
            keyOut.close();
        } catch (IOException e) {
            logger.warn("Failed to close a file: " + keyFile, e);
        }
    }
}
