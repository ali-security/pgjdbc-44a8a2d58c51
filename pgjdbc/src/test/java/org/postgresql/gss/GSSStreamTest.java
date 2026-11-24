/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Checks that the GSS streams reject tokens that GSSContext reports as replayed, out of sequence
 * or integrity-only, and that reading a token does not change how later writes are wrapped.
 */
public class GSSStreamTest {

  private static final byte[] PAYLOAD = {1, 2, 3, 4};

  /**
   * Builds a fake GSSContext whose unwrap returns the token as-is and reports the given
   * flags, and whose wrap returns the input and reports the privacy given.
   */
  private static GSSContext fakeContext(final boolean duplicate, final boolean old,
      final boolean unseq, final boolean gap, final boolean unwrapPrivacy,
      final boolean wrapPrivacy, final boolean[] wrapRequestedPrivacy) {
    return (GSSContext) Proxy.newProxyInstance(GSSStreamTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, (proxy, method, args) -> {
          String name = method.getName();
          if ("unwrap".equals(name) && args.length == 4) {
            byte[] in = (byte[]) args[0];
            int off = (Integer) args[1];
            int len = (Integer) args[2];
            MessageProp prop = (MessageProp) args[3];
            prop.setPrivacy(unwrapPrivacy);
            prop.setSupplementaryStates(duplicate, old, unseq, gap, 0, null);
            return Arrays.copyOfRange(in, off, off + len);
          }
          if ("wrap".equals(name) && args.length == 4) {
            byte[] in = (byte[]) args[0];
            int off = (Integer) args[1];
            int len = (Integer) args[2];
            MessageProp prop = (MessageProp) args[3];
            if (wrapRequestedPrivacy != null) {
              wrapRequestedPrivacy[0] = prop.getPrivacy();
            }
            prop.setPrivacy(wrapPrivacy);
            return Arrays.copyOfRange(in, off, off + len);
          }
          throw new UnsupportedOperationException(name);
        });
  }

  private static byte[] frame(byte[] token) {
    byte[] res = new byte[token.length + 4];
    res[0] = (byte) (token.length >>> 24);
    res[1] = (byte) (token.length >>> 16);
    res[2] = (byte) (token.length >>> 8);
    res[3] = (byte) token.length;
    System.arraycopy(token, 0, res, 4, token.length);
    return res;
  }

  private static void assertReadFails(GSSContext ctx, String expectedMessagePart) {
    GSSInputStream in = new GSSInputStream(new ByteArrayInputStream(frame(PAYLOAD)), ctx,
        new MessageProp(0, true));
    try {
      in.read(new byte[16], 0, 16);
      fail("Expected IOException containing " + expectedMessagePart);
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expectedMessagePart));
    }
  }

  @Test
  public void readAcceptsEncryptedInSequenceToken() throws IOException {
    GSSContext ctx = fakeContext(false, false, false, false, true, true, null);
    GSSInputStream in = new GSSInputStream(new ByteArrayInputStream(frame(PAYLOAD)), ctx,
        new MessageProp(0, true));
    byte[] buf = new byte[16];
    int n = in.read(buf, 0, 16);
    assertEquals(PAYLOAD.length, n);
    assertArrayEquals(PAYLOAD, Arrays.copyOf(buf, n));
  }

  @Test
  public void readRejectsDuplicateToken() {
    assertReadFails(fakeContext(true, false, false, false, true, true, null), "out of sequence");
  }

  @Test
  public void readRejectsOldToken() {
    assertReadFails(fakeContext(false, true, false, false, true, true, null), "out of sequence");
  }

  @Test
  public void readRejectsUnseqToken() {
    assertReadFails(fakeContext(false, false, true, false, true, true, null), "out of sequence");
  }

  @Test
  public void readRejectsGapToken() {
    assertReadFails(fakeContext(false, false, false, true, true, true, null), "out of sequence");
  }

  @Test
  public void readRejectsIntegrityOnlyToken() {
    assertReadFails(fakeContext(false, false, false, false, false, true, null), "not encrypted");
  }

  @Test
  public void writeRejectsWrapWithoutConfidentiality() {
    GSSContext ctx = fakeContext(false, false, false, false, true, false, null);
    GSSOutputStream out = new GSSOutputStream(new ByteArrayOutputStream(), ctx,
        new MessageProp(0, true), 64);
    try {
      out.write(PAYLOAD);
      out.flush();
      fail("Expected IOException for unencrypted outgoing token");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("confidentiality"));
    }
  }

  @Test
  public void integrityOnlyIncomingTokenDoesNotDowngradeWrites() throws IOException {
    // The driver passes one MessageProp to both streams; unwrap clears its privacy flag.
    MessageProp shared = new MessageProp(0, true);
    boolean[] requested = new boolean[1];
    GSSContext ctx = fakeContext(false, false, false, false, false, true, requested);
    GSSInputStream in = new GSSInputStream(new ByteArrayInputStream(frame(PAYLOAD)), ctx, shared);
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    GSSOutputStream out = new GSSOutputStream(sink, ctx, shared, 64);
    try {
      in.read(new byte[16], 0, 16);
      fail("Expected IOException for integrity-only token");
    } catch (IOException expected) {
      // expected
    }
    out.write(PAYLOAD);
    out.flush();
    assertTrue("wrap must still request confidentiality", requested[0]);
    assertArrayEquals(frame(PAYLOAD), sink.toByteArray());
  }
}
